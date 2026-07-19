package com.doob.mathagent.teacher.sync.mq;

/** Publishes an already-authorized source-sync command without exposing broker details to controllers or schedulers. */
public interface TeacherSourceSyncCommandDispatcher {

    /** Sends a durable command for asynchronous execution. */
    void dispatch(TeacherSourceSyncCommand command);
}
