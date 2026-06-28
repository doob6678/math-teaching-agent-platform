package com.doob.mathagent.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.infrastructure.security.SaTokenSubjectResolver;
import com.doob.mathagent.teaching.entity.TeachingTaskEntity;
import com.doob.mathagent.teaching.mapper.TeachingTaskMapper;
import com.doob.mathagent.teaching.service.RedissonTeachingTaskLockService;
import com.doob.mathagent.teaching.service.SpringAiHandoutDraftService;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClient;

class FrameworkIntegrationUsageTest {

    @Test
    void springAiHandoutServiceUsesOfficialChatClientBuilder() throws Exception {
        assertThat(SpringAiHandoutDraftService.class.getConstructor(ChatClient.Builder.class)).isNotNull();
    }

    @Test
    void teachingTaskMapperUsesMyBatisPlusBaseMapperAndEntityAnnotations() throws Exception {
        assertThat(BaseMapper.class).isAssignableFrom(TeachingTaskMapper.class);
        assertThat(TeachingTaskEntity.class.getAnnotation(TableName.class).value()).isEqualTo("teaching_task");
        assertThat(TeachingTaskEntity.class.getDeclaredField("taskId").getAnnotation(TableId.class)).isNotNull();
    }

    @Test
    void redissonTaskLockUsesLeaseBasedLockApi() {
        AtomicLong leaseMillis = new AtomicLong();
        AtomicBoolean unlocked = new AtomicBoolean(false);
        RLock lock = (RLock) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {RLock.class},
                (proxy, method, args) -> {
                    if ("lock".equals(method.getName()) && args.length == 2) {
                        leaseMillis.set((long) args[0]);
                        assertThat(args[1]).isEqualTo(TimeUnit.MILLISECONDS);
                        return null;
                    }
                    if ("isHeldByCurrentThread".equals(method.getName())) {
                        return true;
                    }
                    if ("unlock".equals(method.getName())) {
                        unlocked.set(true);
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
        RedissonClient redissonClient = (RedissonClient) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {RedissonClient.class},
                (proxy, method, args) -> "getLock".equals(method.getName()) ? lock : defaultValue(method.getReturnType()));
        RedissonTeachingTaskLockService service = new RedissonTeachingTaskLockService(redissonClient);

        String result = service.withTaskLock("task-1", Duration.ofSeconds(3), () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(leaseMillis).hasValue(3000L);
        assertThat(unlocked).isTrue();
    }

    @Test
    void saTokenResolverUsesSaTokenLoginStateWithoutForcingLogin() {
        SaTokenSubjectResolver resolver = new SaTokenSubjectResolver();

        assertThat(resolver.currentLoginIdOrNull()).isNull();
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (boolean.class.equals(returnType)) {
            return false;
        }
        if (long.class.equals(returnType)) {
            return 0L;
        }
        if (int.class.equals(returnType)) {
            return 0;
        }
        if (double.class.equals(returnType)) {
            return 0.0d;
        }
        if (float.class.equals(returnType)) {
            return 0.0f;
        }
        if (short.class.equals(returnType)) {
            return (short) 0;
        }
        if (byte.class.equals(returnType)) {
            return (byte) 0;
        }
        if (char.class.equals(returnType)) {
            return (char) 0;
        }
        return null;
    }
}
