package com.doob.mathagent.teaching.mq;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Sends exactly one opaque task identifier to the lecture-task exchange. */
@Component
public class RabbitLectureTaskPublisher implements LectureTaskPublisher {
    private final RabbitTemplate rabbitTemplate;
    public RabbitLectureTaskPublisher(@Qualifier("lectureTaskRabbitTemplate") RabbitTemplate rabbitTemplate) { this.rabbitTemplate = rabbitTemplate; }
    @Override public void publish(String taskId) { rabbitTemplate.convertAndSend(LectureTaskRabbitConfiguration.EXCHANGE, LectureTaskRabbitConfiguration.ROUTING_KEY, taskId); }
}
