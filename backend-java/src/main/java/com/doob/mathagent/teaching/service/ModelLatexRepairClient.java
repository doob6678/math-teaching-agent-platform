package com.doob.mathagent.teaching.service;

import java.util.Optional;

/**
 * 讲义导出编译失败后的模型语法修复边界。
 *
 * <p>Java 是唯一持有 XeLaTeX 编译器的一侧；确定性 sanitizer 修不了的语法错误会连同
 * 真实编译器错误摘录发回 AI 作者（Python worker），由模型返回修复后的同一份文档。
 * Java 不得自行改写教学语义，因此实现只做传输与状态判断，修复文本必须再经真实编译
 * 成功才会被发布。返回 empty 表示本轮放弃（未配置/网络失败/结构校验被拒），调用方
 * 必须回退既有 recovery-stub 路径，绝不伪造成功。</p>
 */
public interface ModelLatexRepairClient {

    /**
     * @param runId         任务/运行标识，仅用于 worker 侧审计与用量记录
     * @param latexSource   sanitize 后编译失败的完整 XeLaTeX 文档
     * @param compilerError 真实编译器错误摘录（来自 handout.log，已截断）
     * @param turn          第几轮修复（从 1 开始），用于提示词与诊断
     * @return 修复后的完整文档；任何失败都折叠为 empty
     */
    Optional<String> repairLatex(String runId, String latexSource, String compilerError, int turn);
}
