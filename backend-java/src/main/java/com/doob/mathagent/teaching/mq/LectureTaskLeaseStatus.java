package com.doob.mathagent.teaching.mq;

/** Lifecycle states persisted for one lecture-task worker lease. */
public enum LectureTaskLeaseStatus { PENDING, RUNNING, RETRYING, COMPLETED, FAILED }
