package com.doob.mathagent.teaching.mq;

/** Broker boundary: messages deliberately contain only the teaching task ID. */
@FunctionalInterface
public interface LectureTaskPublisher {
    void publish(String taskId);
}
