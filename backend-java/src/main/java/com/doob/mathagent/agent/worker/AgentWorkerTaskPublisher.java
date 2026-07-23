package com.doob.mathagent.agent.worker;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Sends opaque task references after MySQL has persisted the task data. */
@Service
public class AgentWorkerTaskPublisher {
    private final RabbitTemplate rabbitTemplate;
    public AgentWorkerTaskPublisher(@Qualifier("agentWorkerRabbitTemplate") RabbitTemplate rabbitTemplate) { this.rabbitTemplate=rabbitTemplate; }
    public void publish(AgentWorkerTask task) { rabbitTemplate.convertAndSend(AgentWorkerRabbitConfiguration.EXCHANGE, task.agentCode(), new AgentWorkerTaskCommand(task.taskId(), task.workflowId(), task.stageCode(), "")); }
}
