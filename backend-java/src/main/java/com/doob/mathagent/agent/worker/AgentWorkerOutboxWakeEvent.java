package com.doob.mathagent.agent.worker;

/**
 * In-process signal that a new outbox row was enqueued. Consumed only by AgentWorkerTaskOutboxScheduler via
 * a commit-phase listener to run an immediate delivery round; it carries no payload for persistence and is
 * never seen outside the JVM, so losing it is harmless (the 1s poll remains the durability fallback).
 */
public record AgentWorkerOutboxWakeEvent(String taskId) {
}
