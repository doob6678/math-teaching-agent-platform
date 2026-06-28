package com.doob.mathagent.retrieval;

/**
 * 检索审计写入端口。
 *
 * <p>业务服务只依赖该接口，具体写 MySQL 还是跳过审计由基础设施实现决定。
 */
public interface RetrievalAuditSink {

    void record(RetrievalAuditEvent event);
}
