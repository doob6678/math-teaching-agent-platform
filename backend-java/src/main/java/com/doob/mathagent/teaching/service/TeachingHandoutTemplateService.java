package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.vo.TeachingHandoutTemplateResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Backend-owned handout template registry. The first version exposes curated templates and keeps the API shape stable
 * for future user-uploaded PDF/LaTeX templates.
 */
@Service
public class TeachingHandoutTemplateService {

    private final Map<String, TeachingHandoutTemplateProfile> templates;

    public TeachingHandoutTemplateService() {
        LinkedHashMap<String, TeachingHandoutTemplateProfile> map = new LinkedHashMap<>();
        map.put("default_standard", new TeachingHandoutTemplateProfile(
                new TeachingHandoutTemplateResponse(
                        "default_standard",
                        "标准讲义",
                        "builtin",
                        "mixed",
                        "标准教师/学生双版本结构，适合通用知识讲解与练习导出。",
                        "基础讲义",
                        "清爽双栏",
                        List.of("基础", "提高"),
                        List.of("知识梳理", "课堂讲解", "双版本"),
                        null,
                        null,
                        null),
                """
                生成一份可直接打印的标准数学讲义。
                必须包含：学习目标、知识点梳理、方法步骤、例题拆解、分层练习、易错提醒。
                教师版要给出完整讲解链路；学生版保留提示和空白，不直接泄露完整答案。
                必须显式考虑 PDF 版式：页眉写主题和版本，页脚留页码/来源区，教师版和学生版要有颜色与内容区分。
                段落要短，公式优先，避免聊天式解释。
                """,
                false));
        map.put("inverse_student_pdf_v1", new TeachingHandoutTemplateProfile(
                new TeachingHandoutTemplateResponse(
                        "inverse_student_pdf_v1",
                        "反比例函数学生版样式",
                        "builtin",
                        "student",
                        "参考真实学生讲义结构：讲次标题、知识点、注意、题型和编号练习。",
                        "学生讲义",
                        "学霸笔记",
                        List.of("基础", "提高"),
                        List.of("学生版", "知识点", "编号练习"),
                        null,
                        null,
                        null),
                """
                输出必须像学生打印讲义，不像问答。
                结构要求：
                1. 标题使用“第X讲 <主题>”或“专题 <主题>”；
                2. 2-4 个“知识点1/2/3”块，每块先公式再解释；
                3. 必须有“注意”块，写定义域、参数、符号、易错边界；
                4. 必须按“题型1/题型2/练习1/练习2”组织；
                5. 学生版只给提示和空白，答案放到教师版。
                6. 学生版 PDF 要保留页眉、页脚、留白区和浅色提示块，不能像聊天记录。
                """,
                true));
        map.put("teacher_solution_v1", new TeachingHandoutTemplateProfile(
                new TeachingHandoutTemplateResponse(
                        "teacher_solution_v1",
                        "教师详解版",
                        "builtin",
                        "teacher",
                        "面向教师备课与讲评：知识定位、板书步骤、追问点和答案完整展开。",
                        "教师详解",
                        "教案式",
                        List.of("基础", "提高", "压轴"),
                        List.of("教师版", "答案", "板书"),
                        null,
                        null,
                        null),
                """
                生成教师讲评用讲义。
                必须包含：知识定位、板书流程、例题完整解析、关键追问、学生易错点、变式训练答案。
                每个解题步骤要说明为什么这样做，不能只给结论。
                如果题目是专题学习，没有具体题目，也要生成可讲授的例题框架和课堂追问。
                教师版 PDF 要包含页眉页脚、深色方法提示块、答案区和讲评备注区。
                """,
                false));
        map.put("gaokao_topic_drill_v1", new TeachingHandoutTemplateProfile(
                new TeachingHandoutTemplateResponse(
                        "gaokao_topic_drill_v1",
                        "高考题型训练",
                        "builtin",
                        "mixed",
                        "按高考题型方法组织：题型识别、方法模板、难度梯度、限时训练。",
                        "专题训练",
                        "题型卡片",
                        List.of("基础", "提高", "压轴"),
                        List.of("高考", "题型", "分层训练"),
                        null,
                        null,
                        null),
                """
                生成高考专题训练讲义。
                必须按“题型识别 -> 方法模板 -> 典型例题 -> 变式训练 -> 难度升级”组织。
                每个题型写出识别信号、常用方法、易错点和适用条件。
                练习题按 A 基础、B 提高、C 压轴分层；教师版给答案和评分点，学生版隐藏答案。
                高考训练 PDF 要有清晰页眉、难度标签、题型编号、页脚和可打印留白。
                """,
                false));
        map.put("space_vector_reference_v1", new TeachingHandoutTemplateProfile(
                new TeachingHandoutTemplateResponse(
                        "space_vector_reference_v1",
                        "空间向量参考讲义",
                        "local_reference",
                        "mixed",
                        "参考本机真实数学讲义《参考讲义数学空间向量.pdf》的专题讲义风格。",
                        "参考模板",
                        "专题讲义",
                        List.of("提高", "压轴"),
                        List.of("空间向量", "立体几何", "本机参考"),
                        "参考讲义数学空间向量.pdf",
                        "文档/项目测试数据位置/参考讲义数学空间向量.pdf",
                        "本机项目测试资料中的空间向量专题讲义。"),
                """
                参考本机真实空间向量专题讲义风格。
                讲义要突出：建系、设点、法向量、线面角、二面角、距离计算。
                版式上使用“核心方法”“例题”“变式”“总结”分段，避免大段散文。
                几何题要强调图形关系和步骤，不要把图形信息写成无结构长段。
                PDF 版式要区分教师/学生版本，保留页眉页脚和适合批注的留白。
                """,
                false));
        for (TeachingHandoutTemplateProfile profile : new TeachingHandoutLocalReferenceScanner().scan()) {
            map.putIfAbsent(profile.summary().templateCode(), profile);
        }
        this.templates = Collections.unmodifiableMap(map);
    }

    /**
     * Lists frontend-visible templates in stable order.
     */
    public List<TeachingHandoutTemplateResponse> list() {
        return templates.values().stream()
                .map(TeachingHandoutTemplateProfile::summary)
                .toList();
    }

    /**
     * Resolves one template code, falling back to the standard template when absent or unknown.
     */
    public TeachingHandoutTemplateProfile resolve(String templateCode) {
        if (templateCode == null || templateCode.isBlank()) {
            return templates.get("default_standard");
        }
        return Optional.ofNullable(templates.get(templateCode.strip()))
                .orElseGet(() -> templates.get("default_standard"));
    }
}
