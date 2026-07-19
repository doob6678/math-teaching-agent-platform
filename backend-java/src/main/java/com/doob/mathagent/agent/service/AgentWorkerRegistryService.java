package com.doob.mathagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doob.mathagent.agent.dto.AgentWorkerHeartbeatRequest;
import com.doob.mathagent.agent.dto.AgentWorkerRegistrationRequest;
import com.doob.mathagent.agent.entity.AgentWorkerNodeEntity;
import com.doob.mathagent.agent.mapper.AgentWorkerNodeMapper;
import com.doob.mathagent.agent.worker.AgentWorkerNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

/** Control-plane registry that records real worker liveness independently from user workflow state. */
@Service
public class AgentWorkerRegistryService {
    private final AgentWorkerNodeMapper mapper;
    private final ObjectMapper objectMapper;
    public AgentWorkerRegistryService(AgentWorkerNodeMapper mapper, ObjectMapper objectMapper) { this.mapper=mapper; this.objectMapper=objectMapper; }
    public AgentWorkerNode register(AgentWorkerRegistrationRequest request) {
        if (request == null || request.workerId() == null || request.workerId().isBlank() || request.supportedAgents() == null || request.supportedAgents().isEmpty()) throw new IllegalArgumentException("workerId and supportedAgents are required");
        AgentWorkerNodeEntity entity = mapper.selectById(request.workerId().strip());
        if (entity == null) { entity = new AgentWorkerNodeEntity(); entity.setWorkerId(request.workerId().strip()); }
        entity.setWorkerVersion(request.workerVersion() == null ? "unknown" : request.workerVersion().strip());
        try { entity.setSupportedAgentsJson(objectMapper.writeValueAsString(request.supportedAgents())); } catch (Exception exception) { throw new IllegalArgumentException("supportedAgents is invalid", exception); }
        entity.setMaxConcurrency(Math.max(1, request.maxConcurrency())); entity.setCurrentLoad(0); entity.setCompletedTaskCount(0L); entity.setFailedTaskCount(0L); entity.setStatus("ONLINE"); entity.setLastHeartbeatAt(Instant.now());
        if (mapper.selectById(entity.getWorkerId()) == null) mapper.insert(entity); else mapper.updateById(entity);
        return toNode(entity);
    }
    public AgentWorkerNode heartbeat(String workerId, AgentWorkerHeartbeatRequest request) {
        AgentWorkerNodeEntity entity = mapper.selectById(required(workerId)); if (entity == null) throw new IllegalArgumentException("Agent Worker is not registered");
        entity.setCurrentLoad(Math.max(0, request.currentLoad())); entity.setCompletedTaskCount(Math.max(0, request.completedTaskCount())); entity.setFailedTaskCount(Math.max(0, request.failedTaskCount())); entity.setLastErrorSummary(request.lastErrorSummary()); entity.setStatus("ONLINE"); entity.setLastHeartbeatAt(Instant.now()); mapper.updateById(entity); return toNode(entity);
    }
    public List<AgentWorkerNode> nodes() { return mapper.selectList(new LambdaQueryWrapper<AgentWorkerNodeEntity>().orderByDesc(AgentWorkerNodeEntity::getLastHeartbeatAt)).stream().map(this::toNode).toList(); }
    /** Marks only stale online nodes offline; a later heartbeat is permitted to rejoin the same stable worker id. */
    public int markOffline(Duration heartbeatTimeout) {
        Instant cutoff = Instant.now().minus(heartbeatTimeout);
        return mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentWorkerNodeEntity>()
                .eq(AgentWorkerNodeEntity::getStatus, "ONLINE")
                .lt(AgentWorkerNodeEntity::getLastHeartbeatAt, cutoff)
                .set(AgentWorkerNodeEntity::getStatus, "OFFLINE"));
    }
    private AgentWorkerNode toNode(AgentWorkerNodeEntity entity) { try { return new AgentWorkerNode(entity.getWorkerId(), entity.getWorkerVersion(), List.of(objectMapper.readValue(entity.getSupportedAgentsJson(), String[].class)), entity.getMaxConcurrency() == null ? 1 : entity.getMaxConcurrency(), entity.getCurrentLoad() == null ? 0 : entity.getCurrentLoad(), entity.getStatus(), entity.getLastHeartbeatAt(), entity.getCompletedTaskCount() == null ? 0L : entity.getCompletedTaskCount(), entity.getFailedTaskCount() == null ? 0L : entity.getFailedTaskCount(), entity.getLastErrorSummary()); } catch (Exception exception) { throw new IllegalStateException("Agent Worker registry is corrupt", exception); } }
    private static String required(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("workerId is required"); return value.strip(); }
}
