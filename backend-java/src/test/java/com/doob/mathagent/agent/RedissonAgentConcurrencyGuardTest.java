package com.doob.mathagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.AgentConcurrencyLease;
import com.doob.mathagent.agent.service.RedissonAgentConcurrencyGuard;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class RedissonAgentConcurrencyGuardTest {

    @Test
    void acquiresLeaseBasedLocksAndReleasesThem() {
        FakeRedisson redisson = new FakeRedisson(true);
        RedissonAgentConcurrencyGuard guard =
                new RedissonAgentConcurrencyGuard(redisson.client(), "math-agent:test:agent-concurrency");

        Optional<AgentConcurrencyLease> lease = guard.tryAcquire(
                List.of("concurrent:user:teacher-1:CoursewareAgent", "concurrent:model:gpt-4.1"),
                "trace-1",
                Duration.ofSeconds(45));

        assertThat(lease).isPresent();
        assertThat(redisson.lockNames()).containsExactly(
                "math-agent:test:agent-concurrency:concurrent:user:teacher-1:CoursewareAgent",
                "math-agent:test:agent-concurrency:concurrent:model:gpt-4.1");
        assertThat(redisson.leaseMillis()).containsOnly(45000L);

        lease.get().close();

        assertThat(redisson.unlockCount()).isEqualTo(2);
    }

    @Test
    void releasesPreviouslyAcquiredLocksWhenLaterLockIsDenied() {
        FakeRedisson redisson = new FakeRedisson(false);
        RedissonAgentConcurrencyGuard guard =
                new RedissonAgentConcurrencyGuard(redisson.client(), "math-agent:test:agent-concurrency");

        Optional<AgentConcurrencyLease> lease = guard.tryAcquire(
                List.of("concurrent:user:teacher-1:CoursewareAgent", "concurrent:model:gpt-4.1"),
                "trace-1",
                Duration.ofSeconds(45));

        assertThat(lease).isEmpty();
        assertThat(redisson.unlockCount()).isEqualTo(1);
    }

    private static final class FakeRedisson {
        private final boolean allLocksAllowed;
        private final List<String> lockNames = new ArrayList<>();
        private final List<Long> leaseMillis = new ArrayList<>();
        private int tryLockCalls;
        private int unlockCount;

        FakeRedisson(boolean allLocksAllowed) {
            this.allLocksAllowed = allLocksAllowed;
        }

        RedissonClient client() {
            return (RedissonClient) Proxy.newProxyInstance(
                    RedissonClient.class.getClassLoader(),
                    new Class<?>[] {RedissonClient.class},
                    (proxy, method, args) -> {
                        if ("getLock".equals(method.getName())) {
                            lockNames.add(String.valueOf(args[0]));
                            return lock();
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private RLock lock() {
            return (RLock) Proxy.newProxyInstance(
                    RLock.class.getClassLoader(),
                    new Class<?>[] {RLock.class},
                    (proxy, method, args) -> {
                        if ("tryLock".equals(method.getName()) && args.length == 3) {
                            tryLockCalls += 1;
                            leaseMillis.add((Long) args[1]);
                            assertThat(args[0]).isEqualTo(0L);
                            assertThat(args[2]).isEqualTo(TimeUnit.MILLISECONDS);
                            return allLocksAllowed || tryLockCalls == 1;
                        }
                        if ("isHeldByCurrentThread".equals(method.getName())) {
                            return true;
                        }
                        if ("unlock".equals(method.getName())) {
                            unlockCount += 1;
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        List<String> lockNames() {
            return lockNames;
        }

        List<Long> leaseMillis() {
            return leaseMillis;
        }

        int unlockCount() {
            return unlockCount;
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
            return null;
        }
    }
}
