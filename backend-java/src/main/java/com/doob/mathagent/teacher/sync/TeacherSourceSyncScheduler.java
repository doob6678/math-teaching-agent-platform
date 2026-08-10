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
import java.time.Instant;

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
    private final TeacherSourceSyncCheckpointStore checkpointStore;
    private final TeacherSourceSyncManifestStore manifestStore;

    public TeacherSourceSyncScheduler(
            TeacherResourceStore resourceStore,
            TeacherSourceSyncJobStore jobStore,
            TeacherSourceSyncJobService jobService,
            TeacherSourceSyncCommandDispatcher commandDispatcher,
            TeacherSourceSyncSchedulerProperties properties,
            RedissonClient redissonClient,
            TeacherSourceSyncCheckpointStore checkpointStore,
            TeacherSourceSyncManifestStore manifestStore) {
        this.resourceStore = resourceStore;
        this.jobStore = jobStore;
        this.jobService = jobService;
        this.commandDispatcher = commandDispatcher;
        this.properties = properties;
        this.redissonClient = redissonClient;
        this.checkpointStore = checkpointStore;
        this.manifestStore = manifestStore;
    }

    // The initial delay lets database connectivity and dependent workers settle before the first recovery sweep.
    // Later ticks remain fixed-delay so a long sync cannot overlap the next sweep.
    @Scheduled(
            initialDelayString = "${math-agent.teacher.sync.scheduler.initial-delay-ms:60000}",
            fixedDelayString = "${math-agent.teacher.sync.scheduler.fixed-delay-ms}")
    public void synchronizeRegisteredFeishuResources() {
        Instant now = Instant.now();
        manifestStore.recoverExpiredLeases(now);
        jobStore.recoverStaleRunningJobs(now, properties.workerLeaseTimeoutSeconds());
        for (TeacherResourceDocumentResponse document : resourceStore.listSchedulableFeishu(properties.tenantId())) {
            if (!properties.documentIds().isEmpty() && !properties.documentIds().contains(document.documentId())) {
                continue;
            }
            String lockName = "math-agent:teacher-source-sync:" + document.tenantId() + ":" + document.documentId();
            RLock lock = redissonClient.getLock(lockName);
            if (!lock.tryLock()) {
                continue;
            }
            try {
                TeacherSourceSyncJobResponse active = jobStore.findActiveByDocument(document.tenantId(), document.documentId());
                if (active != null && "AUTH_REQUIRED".equalsIgnoreCase(active.status())) {
                    // Permission repair is an explicit human action; repeatedly calling Feishu cannot fix it.
                    continue;
                }
                if (active != null && "paused".equalsIgnoreCase(active.status())) {
                    if (checkpointStore.findByJobId(document.tenantId(), active.jobId()).isEmpty()) {
                        LOGGER.warn("Skipping paused Feishu sync without checkpoint document={} job={}", document.documentId(), active.jobId());
                        continue;
                    }
                    commandDispatcher.dispatch(new TeacherSourceSyncCommand(
                            TeacherSourceSyncCommand.CURRENT_SCHEMA_VERSION, TeacherSourceSyncCommand.RESUME,
                            document.tenantId(), properties.serviceRole(), properties.serviceSubjectId(),
                            document.documentId(), active.jobId()));
                    continue;
                }
                // Do not execute a queued manual job: its execute action remains user controlled.
                if (active != null) {
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
