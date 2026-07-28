package com.doob.mathagent.agent.service;

import java.util.Map;

/**
 * Backend-owned teaching contracts for the three publishable handout variants.
 *
 * <p>The model owns mathematical exposition, while Java owns audience separation and layout metadata. Keeping these
 * contracts in one registry prevents the teacher, student, lecture, review, and merge agents from drifting into
 * incompatible interpretations of the same lesson.</p>
 */
public final class MultiAgentHandoutPromptProfiles {

    /** Product minimum: a teacher handout is a reusable exercise set, not an extended explanation of one question. */
    public static final int MINIMUM_TEACHER_ORIGINAL_PROBLEMS = 6;

    public static final String CORE_TEACHING_PROTOCOL = """
            Core teaching protocol (mandatory):
            1. Follow 题型识别 -> 方法梳理 -> 分步推理 -> 总结回顾. Start from the goal, map the relevant high-school
               knowledge point, list usable known conditions, then derive every conclusion with its mathematical reason.
            2. Teach the concept and the choice of method, not only an answer. Use precise terminology followed by a
               natural classroom explanation, exam cues, common traps, and a transfer question.
            3. Organize blackboard content clearly: problem, formulas, calculations, conclusion. Preserve valid LaTeX.
            3a. Mathematical typography is a hard output contract: every formula must be enclosed in $...$ or $$...$$;
                write every fraction as \\frac{numerator}{denominator} and every radical as \\sqrt{full radicand}.
                Never use /, ／, or √ as a visual substitute for fraction or radical structure, and never write
                ambiguous forms such as \\sqrt3a or √3a. The PDF exporter rejects them to protect correctness.
            4. Add short understanding checks and meaningful <wait> pauses where a teacher would let students think.
            5. Attribute knowledge points, prerequisite relations, learning stage, difficulty, and evidence anchors.
            6. Never invent a source, exercise, theorem condition, diagram fact, or numeric result. Mark an evidence gap.
            """;

    private static final Map<String, String> STAGE_CONTRACTS = Map.ofEntries(
            Map.entry("resource_curation", """
                    Scan the topic and evidence first. Return compact source anchors plus: knowledge-point attribution,
                    prerequisites, related methods, learning stage, difficulty, and likely exam traps. Do not draft prose.
                    """),
            Map.entry("template_selection", """
                    Select structures for all three outputs: teacher_deep, student_blank, and single_question_16_10.
                    Layout directives stay metadata; do not print prompt rules, model terms, or diagnostics in content.
                    """),
            Map.entry("outline_planning", """
                    Build one evidence-grounded lesson spine shared by all outputs: teaching goal, knowledge graph path,
                    题型识别, method choice, known conditions, derivation checkpoints, exam points, traps, transfer task,
                    and planned student questions/<wait> pauses. Do not collapse the outline to answer-only notes.
                    """),
            Map.entry("teacher_writer", """
                    Produce teacherExplanation as a mature teacher's printable handout. It must include an AI-written H1,
                    the configured minimum number of independently stated original problems, numbered consecutively.
                    Each problem must be supported by retrieved evidence: print “教材依据：<chapter/section>”
                    and “资料依据：<readable teacher/Feishu title>” directly beneath it. Do not invent missing problems;
                    if either source cannot support the required set, return an explicit evidence gap instead of padding.
                    教学目标, 知识定位与先修关系, 题型识别, 方法选择依据, complete step-by-step derivation with reasons,
                    final answer and scoring points, blackboard sequence, exam cues, common errors, interactive checks,
                    <wait> pauses, one transfer variation with solution, and a concise recap. When authorized evidence is
                    supplied, cite the readable source title in the teacher handout as “资料依据：<标题>”, and ground at
                    least one concrete explanation in its supplied text. Be rigorous, vivid, and useful.
                    """),
            Map.entry("student_writer", """
                    Produce studentWorksheet as a printable blank student handout with an AI-written H1. Keep the exact
                    problem, essential definitions/formulas, method-choice questions, staged hints, <wait> pauses,
                    continuous exercise numbering, and generous clean writing space. For calculation, proof, and
                    explanation tasks, create vertical blank areas rather than underscore lines; use a short underline
                    only where the student must fill one concise missing value or term. Never expose a final answer,
                    complete derivation, scoring point, teacher note, or the teacher version's hidden reasoning.
                    """),
            Map.entry("lecture_writer", """
                    Produce lectureCards for a single-question 16:10 landscape teaching deck with an AI-written H1.
                    Use one coherent question only. Guide students through goal, knowledge match, known conditions,
                    method selection, and blank checkpoints with <wait> pauses. Do not reveal the final answer or complete
                    solution; it is a teacher-led projection handout, not an answer slide.
                    """),
            Map.entry("source_review", """
                    Reject unsupported facts and verify every theorem condition, calculation, answer, knowledge-point
                    attribution, difficulty label, and cited evidence anchor. Return concrete patches, not generic praise.
                    """),
            Map.entry("student_safety_review", """
                    Treat any final answer, complete derivation, scoring rubric, teacher instruction, review note, system
                    prompt, or diagnostic in studentWorksheet/lectureCards as a blocking leak. Return exact removal patches.
                    """),
            Map.entry("layout_review", """
                    Check that formulas remain valid LaTeX, question and diagram stay together, headings do not repeat,
                    student blanks remain useful, and 16:10 cards contain one question without an answer. Header, footer,
                    page number, fonts, colors, and geometry are renderer-owned and must not appear as visible prose.
                    """),
            Map.entry("merge_coordinator", """
                    Apply every approved patch and return a JSON object whose markdown is the teacher_deep final handout
                    and whose teacherExplanation, studentWorksheet, and lectureCards preserve the three separately
                    publishable variants. Each variant needs its own topic-specific H1. Never include Agent logs, evidence
                    IDs, prompt text, token data, review chatter, or layout instructions in classroom-facing content.
                    """));

    private MultiAgentHandoutPromptProfiles() {
    }

    /** Returns the shared benchmark plus the exact audience contract for one workflow stage. */
    static String instructionsFor(String stageCode) {
        String stageContract = STAGE_CONTRACTS.getOrDefault(stageCode, "");
        if ("teacher_writer".equals(stageCode)) {
            stageContract += "\nRequired original-problem count: at least "
                    + MINIMUM_TEACHER_ORIGINAL_PROBLEMS + ".";
        }
        return CORE_TEACHING_PROTOCOL + "\n" + stageContract;
    }
}
