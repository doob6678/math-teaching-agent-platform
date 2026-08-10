package com.doob.mathagent.agent.mapper;

import com.doob.mathagent.agent.service.HandoutRunMetricsStore.MetricsRow;
import com.doob.mathagent.agent.service.HandoutRunMetricsStore.LifecycleRow;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis boundary for the Java-owned queue/lease portion of handout telemetry. */
@Mapper
public interface HandoutRunMetricsMapper {

    /** Creates the metrics row before the opaque RabbitMQ command is published. */
    int recordEnqueued(LifecycleRow row);

    /** Creates or updates the row before Python starts, preserving the queue wait observed by Java. */
    int recordClaim(MetricsRow row);

    /** Records the terminal Java-side ACK boundary after the Python graph has returned. */
    int recordTerminal(MetricsRow row);

    /** Stores the AMQP-to-lease acquisition latency. */
    int recordLeaseWait(LifecycleRow row);

    /** Increments a durable dead-letter count after retry exhaustion. */
    int recordDeadLetter(LifecycleRow row);

    /** Updates only the publication-gate timestamp and leaves Python-owned fields untouched. */
    int recordPublicationGate(LifecycleRow row);

    /** Updates only XeLaTeX timing so export telemetry cannot overwrite provider accounting. */
    int recordPdf(LifecycleRow row);
}
