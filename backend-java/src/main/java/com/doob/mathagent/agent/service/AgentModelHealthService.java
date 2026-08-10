package com.doob.mathagent.agent.service;

import com.doob.mathagent.agent.vo.AgentModelHealthResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 通过 Python Worker 运行 provider 可达性检查。
 *
 * <p>Java 只投影可公开的脱敏结果，不再向 provider 直接发送 health request。</p>
 */
@Service
public class AgentModelHealthService {

    private final PythonMigratedWorkloadClient workloadClient;
    private final Clock clock;

    @Autowired
    public AgentModelHealthService(PythonMigratedWorkloadClient workloadClient) {
        this(workloadClient, Clock.systemUTC());
    }

    /** 用可注入时钟创建服务，供确定性 facade 测试使用。 */
    public AgentModelHealthService(PythonMigratedWorkloadClient workloadClient, Clock clock) {
        this.workloadClient = workloadClient;
        this.clock = clock;
    }

    /** 返回 Python probe 执行的每项 provider/model 脱敏可达性。 */
    public AgentModelHealthResponse checkHealth() {
        Instant checkedAt = Instant.now(clock);
        List<AgentModelHealthResponse.Result> results = workloadClient.providerHealth(UUID.randomUUID().toString())
                .stream()
                .map(result -> new AgentModelHealthResponse.Result(
                        result.providerName(),
                        result.modelCode(),
                        result.configured(),
                        result.reachable(),
                        result.statusCode(),
                        result.elapsedMs(),
                        result.safeReason(),
                        checkedAt))
                .toList();
        return new AgentModelHealthResponse(checkedAt, results);
    }
}
