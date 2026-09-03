package com.doob.mathagent.infrastructure.security;

import java.time.Duration;
import java.time.Instant;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

/**
 * 基于 Redisson RRateLimiter 的 Redis 分布式限流器，替换旧的自研
 * {@code RedisFixedWindowRateLimiter}（INCR + 条件 EXPIRE 两步非原子计数，该类已删除）。
 *
 * <p>与旧实现的行为差异（均为有意为之）：</p>
 * <ul>
 *   <li>原子性：旧实现先 INCR 再在计数为 1 时 EXPIRE，两次命令之间实例崩溃会留下永不过期的
 *       计数 key，窗口从此失效；Redisson 的 tryAcquire 由内置 Lua 脚本一次往返完成
 *       "取令牌 + 回收过期令牌 + 刷新 TTL"，不存在半成品状态。</li>
 *   <li>窗口模型：旧实现是日历对齐的固定窗口（epoch 整除窗口长度），窗口边界两侧最坏可放行
 *       约 2 倍限额；RRateLimiter 为每个令牌打上"存活 window 秒"的滑动语义，任意滚动 window
 *       内最多 limit 次，速率上限更严格。</li>
 *   <li>TTL 语义：旧实现依赖首次创建 key 时显式 EXPIRE（window+1s）；新实现通过 trySetRate 的
 *       keepAliveTime（2×window）在每次 acquire 时刷新限流器全部 key 的 TTL——空闲自动清理、
 *       活跃不残留，同样满足"限流 key 不无限增长"。</li>
 *   <li>时钟来源：旧实现用应用实例传入的 {@code now} 计算窗口起点，多实例时钟漂移会切歪窗口；
 *       新实现的窗口完全以 Redis 服务端时钟为准，{@code now} 参数因此被忽略（见 check 方法）。</li>
 *   <li>配置变更生效：限流器名把 limit 与窗口秒数编进名字（见 {@link #limiterName}），调整策略
 *       后新请求立刻命中新速率，旧名限流器失去流量后按 keepAlive 自动过期，不会残留旧速率。</li>
 * </ul>
 *
 * <p>失败语义与旧实现一致：故意不加 try/catch。Redis 不可用时异常直接上抛、请求得到 500，
 * 而不是静默放行高成本 AI 接口（fail-closed）。</p>
 */
public class RedissonApiRateLimiter implements ApiRateLimiter {

    private final RedissonClient redissonClient;
    private final String keyPrefix;

    /**
     * 创建 Redisson 限流器。
     *
     * @param redissonClient 复用项目统一的 Redisson 客户端（RedissonClientConfiguration 提供），
     *                       不另行新建连接池。
     * @param keyPrefix 限流 key 前缀，用于隔离应用、环境和限流用途，来自
     *                  {@link RedisRateLimitProperties#keyPrefix()}。
     */
    public RedissonApiRateLimiter(RedissonClient redissonClient, String keyPrefix) {
        this.redissonClient = redissonClient;
        this.keyPrefix = keyPrefix;
    }

    /**
     * 尝试消耗一个令牌并换算成 {@link RateLimitUsage}，保持调用方（ApiAccessControlService/
     * ApiAccessControlFilter）读取 used/limit 写响应头与判断 exceeded() 的既有契约。
     *
     * <p>参数 {@code now} 被有意忽略：窗口起点与令牌过期判断全部在 Redisson 的 Lua 脚本内用
     * Redis 服务端时钟完成，多实例部署时不受本机墙钟漂移影响；旧实现依赖 {@code now} 对齐固定
     * 窗口，接口契约保留该参数只是为了不破坏本地实现 FixedWindowRateLimiter。</p>
     *
     * <p>本方法不做 Redis 异常兜底（fail-closed），理由见类注释。</p>
     */
    @Override
    public RateLimitUsage check(String key, int limit, Duration window, Instant now) {
        RRateLimiter limiter = redissonClient.getRateLimiter(limiterName(key, limit, window));
        // trySetRate 仅在限流器首次创建时写入速率（已存在则返回 false 且不覆盖），每次请求都调用是幂等的，
        // 这样多实例、重启后都能保证限流器已初始化；keepAlive=2*window 覆盖一个完整窗口再加一倍缓冲，
        // 保证窗口内的令牌不被提前清除，同时让彻底空闲的限流 key 自动过期回收。
        limiter.trySetRate(RateType.OVERALL, limit, window, window.multipliedBy(2));
        if (!limiter.tryAcquire(1)) {
            // 被拒绝的请求不消耗令牌；上报 used=limit+1 让 RateLimitUsage.exceeded() 为 true，
            // 与旧实现超限计数的语义近似一致（旧实现会累加到 limit+n，响应头 X-RateLimit-Used 本就是近似值）。
            return new RateLimitUsage(limit, limit + 1);
        }
        // availablePermits 读取 Redis 侧剩余令牌数，used = limit - 剩余；夹到 [1, limit] 防御
        // 并发回收令牌导致的瞬时越界（本次已放行至少 1 次，used 不可能为 0）。
        long available = limiter.availablePermits();
        int used = (int) Math.min(limit, Math.max(1L, limit - available));
        return new RateLimitUsage(limit, used);
    }

    /**
     * 拼限流器名：{@code 前缀:维度key:limit:窗口秒}。limit 与窗口编进名字的原因：策略配置变更后
     * 名字即变，新速率立即生效，无需等待旧窗口翻转；旧名字的限流器不再收到流量，keepAlive 到期
     * 后由 Redis 自动删除，集群里不会残留按旧 limit 运行的限流器。
     */
    private String limiterName(String key, int limit, Duration window) {
        return "%s:%s:%d:%d".formatted(keyPrefix, key, limit, window.toSeconds());
    }
}
