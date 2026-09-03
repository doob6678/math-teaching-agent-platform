package com.doob.mathagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.controller.AgentToolBrokerController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Opt-in full-Spring-context startup smoke against the local Compose stack.
 *
 * <p>Why this exists: the 726-test suite never boots the complete application context, so on 2026-08-31 a
 * package-refactor phantom mapper bean (FeishuDriveClient scanned as a MyBatis mapper) and duplicate
 * TeacherDocumentBlockMapper shadows passed "green" unit runs while the deployed backend crash-looped on
 * startup. A context-load is the only test shape that catches bean-definition scanning conflicts, so this
 * smoke closes that gap without making the default build environment-dependent.</p>
 *
 * <p>Gating: skipped unless both {@code -Dmathagent.smoke=true} and MYSQL_ROOT_PASSWORD are present, so CI
 * and offline runs skip cleanly. Host ports are the Compose NAT mappings (MySQL 3307, Redis 6380, RabbitMQ
 * 5674, Milvus 19531) because the container service names do not resolve from the Windows host.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "math-agent.database.url=jdbc:mysql://127.0.0.1:3307/math_agent_rag?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.rabbitmq.host=127.0.0.1",
                "spring.rabbitmq.port=5674",
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=6380",
                "spring.data.redis.url=redis://:${REDIS_PASSWORD:}@127.0.0.1:6380",
                "math-agent.redis.redisson.address=redis://127.0.0.1:6380",
                "math-agent.vector-index.milvus-uri=http://127.0.0.1:19531",
                // Keep this process a pure context verifier: no consumer may claim real durable tasks.
                "math-agent.rabbitmq.listeners-enabled=false",
        })
@EnabledIfSystemProperty(named = "mathagent.smoke", matches = "true")
@EnabledIfEnvironmentVariable(named = "MYSQL_ROOT_PASSWORD", matches = ".+")
class SpringContextStartupSmokeTest {

    @Test
    void applicationContextRefreshesAgainstRealInfrastructure(ApplicationContext context) {
        // The 2026-08-31 failures happened during bean-definition scanning/refresh, so a reached assertion
        // here already proves the conflict is gone; the broker controller check additionally proves the
        // worker-key authorization bean graph (its constant-time authorize path) is fully wired.
        assertThat(context.getBean(AgentToolBrokerController.class)).isNotNull();
    }
}
