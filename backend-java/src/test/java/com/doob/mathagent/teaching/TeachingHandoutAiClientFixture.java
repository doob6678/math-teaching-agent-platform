package com.doob.mathagent.teaching;

import com.doob.mathagent.teaching.service.TeachingHandoutAiClient;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.util.List;

/** 为工作流行为测试提供已投影的 Python handout 图结果。 */
final class TeachingHandoutAiClientFixture {

    private TeachingHandoutAiClientFixture() {
    }

    static TeachingHandoutAiClient completed() {
        return (taskId, request, evidence) -> draft(
                "【知识定位】" + request.learningGoal() + "。\n"
                        + "【题型识别】先识别题目条件与目标。\n"
                        + "【方法步骤】1. 根据来源条件建立关系。\n2. 分步推导并核验结论。\n"
                        + "【例题详解】围绕已核验题目组织推导。\n"
                        + "【答案与评分点】结论必须对应来源条件。\n"
                        + "【易错提醒】注意边界条件与符号。\n"
                        + "【课堂追问】条件变化后结论如何调整？",
                "【知识速记】先提取条件，再选择对应方法。\n"
                        + "【题型识别】识别已知量与待求量。\n"
                        + "【例题任务】写出第一步关系。\n"
                        + "【练习任务】1. 独立完成同类推导。\n"
                        + "【作答提醒】每一步都回到题目条件。",
                "课堂投影：逐步说明条件、方法与结论。",
                List.of("按来源条件建立关系并完成推导"),
                List.of());
    }

    static TeachingHandoutAiClient failing(RuntimeException failure) {
        return (taskId, request, evidence) -> {
            throw failure;
        };
    }

    static TeachingHandoutAiClient failing(Error failure) {
        return (taskId, request, evidence) -> {
            throw failure;
        };
    }

    static TeachingHandoutAiClient fromDraft(
            String teacherExplanation, String studentHint, List<String> knowledgePoints, List<String> lectureLines) {
        return fromDocuments(
                teacherExplanation,
                studentHint,
                String.join("\n", lectureLines),
                knowledgePoints,
                List.of());
    }

    static TeachingHandoutAiClient fromDocuments(
            String teacherExplanation,
            String studentHint,
            String lectureContent,
            List<String> knowledgePoints,
            List<String> followUpQuestions) {
        return (taskId, request, evidence) -> draft(
                teacherExplanation, studentHint, lectureContent, knowledgePoints, followUpQuestions);
    }

    private static TeachingTaskResponse.AiDraft draft(
            String teacherExplanation,
            String studentHint,
            String lectureContent,
            List<String> knowledgePoints,
            List<String> followUpQuestions) {
        return new TeachingTaskResponse.AiDraft(
                true,
                "python-test",
                "handout-test-v1",
                21,
                13,
                34,
                String.join("\n\n", teacherExplanation, studentHint, lectureContent),
                "Python handout graph completed.",
                true,
                teacherExplanation,
                studentHint,
                lectureContent,
                List.copyOf(knowledgePoints),
                List.copyOf(followUpQuestions),
                "",
                0,
                0,
                false,
                List.of(new TeachingTaskResponse.AiRecoveryEvent(
                        "PYTHON_HANDOUT_TEACHER_WRITER",
                        "python-test",
                        "handout-test-v1",
                        1,
                        true,
                        false,
                        "completed")));
    }
}
