package com.doob.mathagent.teacher.sync;

import com.doob.mathagent.teacher.document.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.sync.mq.TeacherSourceSyncCommand;
import com.doob.mathagent.teacher.sync.mq.TeacherSourceSyncCommandDispatcher;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs configured Feishu syncs through the exact same durable job and execution services as the manual endpoint.
 *
 * The database active-job unique key is the cross-instance lock. This scheduler only claims a resource when no
 * manual/scheduled job is already active, preventing concurrent downloads and vector replacement for one document.
 */
@Component
@ConditionalOnProperty(prefix = "math-agent.teacher.sync.scheduler", name = "enabled", havingValue = "true")
public class TeacherSourceSyncScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TeacherSourceSyncScheduler.class);

    private final TeacherResourceStore resourceStore;
    private final TeacherSourceSyncJobStore jobStore;
    private final TeacherSourceSyncJobService jobService;
    private final TeacherSourceSyncCommandDispatcher commandDispatcher;
    private final TeacherSourceSyncSchedulerProperties properties;
    private final RedissonClient redissonClient;

    public TeacherSourceSyncScheduler(
            TeacherResourceStore resourceStore,
            TeacherSourceSyncJobStore jobStore,
            TeacherSourceSyncJobService jobService,
            TeacherSourceSyncCommandDispatcher commandDispatcher,
            TeacherSourceSyncSchedulerProperties properties,
            RedissonClient redissonClient) {
        this.resourceStore = resourceStore;
        this.jobStore = jobStore;
        this.jobService = jobService;
        this.commandDispatcher = commandDispatcher;
        this.properties = properties;
        this.redissonClient = redissonClient;
    }

    @Scheduled(fixedDelayString = "${math-agent.teacher.sync.scheduler.fixed-delay-ms}")
    public void synchronizeRegisteredFeishuResources() {
        for (TeacherResourceDocumentResponse document : resourceStore.listSchedulableFeishu(properties.tenantId())) {
            if (!properties.documentIds().contains(document.documentId())) {
                continue;
            }
            String lockName = "math-agent:teacher-source-sync:" + document.tenantId() + ":" + document.documentId();
            RLock lock = redissonClient.getLock(lockName);
            if (!lock.tryLock()) {
                continue;
            }
            try {
                // Do not execute a queued manual job: its capability-gated execute action remains user controlled.
                if (jobStore.findActiveByDocument(document.tenantId(), document.documentId()) != null) {
                    continue;
                }
                TeacherSourceSyncJobResponse job = jobService.createSyncJob(
                        document.tenantId(), properties.serviceRole(), properties.serviceSubjectId(), document.documentId());
                if (!"queued".equalsIgnoreCase(job.status())
                        || !properties.serviceSubjectId().equals(job.createdBy())) {
                    continue;
                }
                commandDispatcher.dispatch(new TeacherSourceSyncCommand(
                        TeacherSourceSyncCommand.CURRENT_SCHEMA_VERSION,
                        TeacherSourceSyncCommand.EXECUTE,
                        document.tenantId(),
                        properties.serviceRole(),
                        properties.serviceSubjectId(),
                        document.documentId(),
                        job.jobId()));
            } catch (RuntimeException exception) {
                LOGGER.warn("Scheduled Feishu source sync failed for document {}: {}", document.documentId(),
                        exception.getMessage());
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }
}
