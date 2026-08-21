package com.doob.mathagent.system;

import java.util.function.BooleanSupplier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 将项目既有的完整部署就绪判定纳入 Spring Actuator readiness，避免进程启动后误报可用。
 */
@Component("infrastructureDependencies")
public class InfrastructureDependenciesHealthIndicator implements HealthIndicator {

    private final BooleanSupplier deploymentReady;

    @Autowired
    public InfrastructureDependenciesHealthIndicator(SystemRuntimeStatusService runtimeStatusService) {
        this(() -> runtimeStatusService.status().deployment().ready());
    }

    /** 仅供聚焦测试注入确定的就绪结果。 */
    InfrastructureDependenciesHealthIndicator(BooleanSupplier deploymentReady) {
        this.deploymentReady = deploymentReady;
    }

    /**
     * 复用现有运行状态服务的真实依赖、Milvus 与部署配置检查，保持两个 readiness 端点语义一致。
     *
     * @return 不泄露依赖凭据的 Actuator 健康状态
     */
    @Override
    public Health health() {
        try {
            return deploymentReady.getAsBoolean() ? Health.up().build() : Health.down().build();
        } catch (RuntimeException exception) {
            // 状态聚合失败不能作为服务已就绪的证据。
            return Health.down().build();
        }
    }
}
