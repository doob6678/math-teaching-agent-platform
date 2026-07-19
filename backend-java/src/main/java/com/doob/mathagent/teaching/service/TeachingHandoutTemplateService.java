package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.vo.TeachingHandoutTemplateResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Backend-owned handout template registry. The first version exposes curated templates and keeps the API shape stable
 * for future user-uploaded PDF/LaTeX templates.
 */
@Service
public class TeachingHandoutTemplateService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_SKILL_CONFIG = "文档/开发知识库/讲义模板skills.json";

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
                        null,
                        6,
                        4),
                """
                生成一份可直接打印的标准数学讲义。
                必须包含：学习目标、具体知识点梳理、方法步骤、例题拆解、分层练习、易错提醒。
                教师版要给出完整讲解链路；学生版保留提示和空白，不直接泄露完整答案。
                所有知识点、方法卡和练习都要紧扣当前题目或主题，不要生成“知识点1/2/3”“题型1/2/3”这类占位标题。
                正文要么写实际教学内容，要么保留干净空白，不要写“本讲任务”“课堂任务”“审校清单”这类元说明。
                版式由系统渲染负责，正文不要写页眉、页脚、颜色、模板规则、模型或系统说明。
                段落要短，公式优先，避免聊天式解释和大段散文。
                """,
                false));
        // The user supplied this master as the accepted visual reference.  Registering it explicitly instead of
        // relying on a desktop scan keeps template identity, preview access, and the Zhao PDF renderer stable after
        // a restart or a cache clear.  The path is a user-authorized local source, never model input text.
        map.put("zhao_lixian_2025_master_v1", new TeachingHandoutTemplateProfile(
                new TeachingHandoutTemplateResponse(
                        "zhao_lixian_2025_master_v1",
                        "连续真题讲义母版",
                        "local_reference",
                        "mixed",
                        "按连续真题页、页眉页脚和紧凑层级导出；品牌名称由任务自定义。",
                        "高考一轮讲义",
                        "连续真题页",
                        List.of("基础", "提高", "压轴"),
                        List.of("高考一轮", "连续题组", "真题溯源", "矢量边框"),
                        "2025暑秋讲义.pdf",
                        "D:/BaiduNetdiskDownload/111高考研究/高考数学研究/赵礼显2025/2025高三【赵礼显】/2025一轮复习/2025暑秋讲义.pdf",
                        "用户提供的连续真题讲义参考；用于尺寸、色彩、题组密度和题图同页的视觉对照。",
                        6,
                        3),
                """
                按赵礼显高考一轮讲义的连续真题页组织内容：直接进入知识点和真实题目，不生成通用封面、
                本节目标、来源索引或空泛方法卡来占页。每个知识点只保留已核验的资料依据、条件识别、
                推导和结论；每道题必须紧跟完整题干，题干含“如图”时仅在同源授权图已同步后输出。
                例题与变式只能来自当前知识点命中的真实题库或用户题目，资料不足就省略该题，绝不补造。
                教师版给出可复核的数学理由和答案，学生版只给题目、必要提示和合理作答空间；横版版每题独立。
                正文不得出现模板、页眉页脚、渲染、提示词、OCR、模型或系统工作流说明。
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
                        null,
                        8,
                        4),
                """
                输出必须像学生打印讲义，不像问答。
                标题使用“第X讲 <主题>”或“专题 <主题>”。
                先写具体公式、条件、图像特征，再写对应解释；不要用“知识点1/2/3”“题型1/2/3”当占位块堆页面。
                保留“注意”或“易错提醒”，重点写定义域、参数、符号和边界。
                练习必须连续编号，空白区域集中、紧凑，不要在每个小点后面塞大块空白；空白处不要标“作答区”“手写区”“留白区”。
                学生版只给提示和干净空白，答案放到教师版。
                正文要么写具体内容，要么留空，不要写“本讲任务”“课堂任务”“本页只保留”这类元话术。
                正文只写学生需要看的知识、提示、题目和留白，不写页眉页脚、颜色或系统规则。
                """,
                true));
        map.put("inverse_real_student_reference_v1", new TeachingHandoutTemplateProfile(
                new TeachingHandoutTemplateResponse(
                        "inverse_real_student_reference_v1",
                        "反比例函数真实学生讲义",
                        "local_reference",
                        "student",
                        "直接参考本机真实《反比例函数（学生版）》PDF，用于学生讲义排版和留白风格对齐。",
                        "学生讲义",
                        "学霸笔记",
                        List.of("基础", "提高"),
                        List.of("反比例函数", "学生版", "真实参考", "编号练习"),
                        "反比例函数（学生版）7658488570078855330.pdf",
                        "C:/Users/doob/Documents/xwechat_files/wxid_4o23y4ktrzsx22_7541/msg/file/2026-07/反比例函数（学生版）7658488570078855330.pdf",
                        "本机真实学生版讲义，适合作为学生讲义留白、编号练习和标题层级的参考。",
                        9,
                        4),
                """
                参考本机真实《反比例函数（学生版）》讲义风格，生成学生可直接打印的课堂讲义。
                先写具体定义、表达式、图像性质和题型提示，再写连续编号练习。
                学生版只保留知识点、题目、提示和干净空白，不出现答案、评分点、完整解析。
                每道题之间留出明显空白，但不要标注“作答区”“手写区”“留白区”，也不要过度留白；整体保持真实讲义的紧凑层级。
                正文只写学生要看到的内容，不写页眉页脚、颜色、模板规则、PDF 规则或系统说明。
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
                        null,
                        6,
                        4),
                """
                生成教师讲评用讲义。
                必须包含：知识定位、板书流程、例题完整解析、关键追问、学生易错点、变式训练答案。
                每个解题步骤要说明为什么这样做，不能只给结论。
                如果题目是专题学习，没有具体题目，也要生成可讲授的例题框架和课堂追问。
                正文只写教师备课和讲评内容，不写页眉页脚、颜色、模型、token 或系统诊断。
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
                        null,
                        7,
                        3),
                """
                生成高考专题训练讲义。
                必须按“题型识别 -> 方法模板 -> 典型例题 -> 变式训练 -> 难度升级”组织。
                每个题型写出识别信号、常用方法、易错点和适用条件。
                练习题按 A 基础、B 提高、C 压轴分层；教师版给答案和评分点，学生版隐藏答案。
                正文保留题号、难度、方法和留白，不写页眉页脚、颜色或系统说明。
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
                        "C:/Users/doob/Desktop/code/dev/math_agent_rag/文档/项目测试数据位置/参考讲义数学空间向量.pdf",
                        "本机项目测试资料中的空间向量专题讲义。",
                        7,
                        4),
                """
                参考本机真实空间向量专题讲义风格。
                讲义要突出：建系、设点、法向量、线面角、二面角、距离计算。
                版式上使用“核心方法”“例题”“变式”“总结”分段，避免大段散文。
                几何题要强调图形关系和步骤，不要把图形信息写成无结构长段。
                正文只保留专题讲解、题目、步骤、答案或留白，不写页眉页脚和渲染规则。
                """,
                false));
        Set<String> knownSourceKeys = new LinkedHashSet<>();
        for (TeachingHandoutTemplateProfile profile : map.values()) {
            knownSourceKeys.addAll(templateSourceKeys(profile.summary()));
        }
        for (TeachingHandoutTemplateProfile profile : configuredTemplateSkills()) {
            putTemplateIfNewSource(map, knownSourceKeys, profile);
        }
        for (TeachingHandoutTemplateProfile profile : new TeachingHandoutLocalReferenceScanner().scan()) {
            putTemplateIfNewSource(map, knownSourceKeys, profile);
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

    private static void putTemplateIfNewSource(
            LinkedHashMap<String, TeachingHandoutTemplateProfile> map,
            Set<String> knownSourceKeys,
            TeachingHandoutTemplateProfile profile) {
        if (profile == null || profile.summary() == null || isBlank(profile.summary().templateCode())) {
            return;
        }
        if (map.containsKey(profile.summary().templateCode())) {
            return;
        }
        List<String> keys = templateSourceKeys(profile.summary());
        if (!Collections.disjoint(knownSourceKeys, keys)) {
            return;
        }
        map.put(profile.summary().templateCode(), profile);
        knownSourceKeys.addAll(keys);
    }

    private static List<String> templateSourceKeys(TeachingHandoutTemplateResponse summary) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        addSourceKey(keys, "reference-title", summary.referenceTitle());
        addSourceKey(keys, "reference-path", summary.referencePath());
        addSourceKey(keys, "reference-file", fileNameOf(summary.referencePath()));
        return keys.stream().toList();
    }

    private static void addSourceKey(Set<String> keys, String prefix, String value) {
        String normalized = normalizeSourceIdentity(value);
        if (!normalized.isBlank()) {
            keys.add(prefix + ":" + normalized);
        }
    }

    private static String fileNameOf(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private static String normalizeSourceIdentity(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.strip()
                .replace('\\', '/')
                .replaceFirst("(?i)\\.pdf$", "")
                .replaceAll("\\d{6,}$", "")
                .replaceAll("[\\s_\\-]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static List<TeachingHandoutTemplateProfile> configuredTemplateSkills() {
        List<TeachingHandoutTemplateProfile> profiles = new ArrayList<>();
        for (Path path : configuredSkillFiles()) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                List<ConfiguredTemplateSkill> items = readConfiguredTemplateSkills(path);
                for (ConfiguredTemplateSkill item : items) {
                    toProfile(item).ifPresent(profiles::add);
                }
            } catch (IOException | RuntimeException ignored) {
                // Bad local skill config must not break the backend template registry.
            }
        }
        return profiles;
    }

    private static List<ConfiguredTemplateSkill> readConfiguredTemplateSkills(Path path) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(path.toFile());
        JsonNode templatesNode = root.isArray() ? root : root.get("templates");
        if (templatesNode == null || !templatesNode.isArray()) {
            return List.of();
        }
        return OBJECT_MAPPER.convertValue(
                templatesNode,
                new TypeReference<List<ConfiguredTemplateSkill>>() {
                });
    }

    private static List<Path> configuredSkillFiles() {
        List<Path> paths = new ArrayList<>();
        paths.add(repoRoot(Path.of("").toAbsolutePath().normalize()).resolve(DEFAULT_SKILL_CONFIG));
        String configured = System.getenv("MATH_AGENT_HANDOUT_TEMPLATE_SKILL_FILES");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("math.agent.handout.template.skill.files", "");
        }
        for (String item : configured.split(";")) {
            if (!item.isBlank()) {
                paths.add(Path.of(item.strip()).toAbsolutePath().normalize());
            }
        }
        return paths;
    }

    private static Optional<TeachingHandoutTemplateProfile> toProfile(ConfiguredTemplateSkill item) {
        if (item == null || isBlank(item.templateCode()) || isBlank(item.displayName()) || isBlank(item.promptInstructions())) {
            return Optional.empty();
        }
        String sourceType = isBlank(item.sourceType()) ? "skill_config" : item.sourceType().strip();
        return Optional.of(new TeachingHandoutTemplateProfile(
                new TeachingHandoutTemplateResponse(
                        item.templateCode().strip(),
                        item.displayName().strip(),
                        sourceType,
                        defaultText(item.audience(), "mixed"),
                        defaultText(item.description(), "来自动态 skill 配置的讲义模板。"),
                        defaultText(item.category(), "动态模板"),
                        defaultText(item.visualStyle(), "配置模板"),
                        safeList(item.difficultyBands(), List.of("基础", "提高")),
                        safeList(item.tags(), List.of("动态配置")),
                        emptyToNull(item.referenceTitle()),
                        emptyToNull(item.referencePath()),
                        emptyToNull(item.referencePreview()),
                        item.blankSpaceEm(),
                        item.questionGapEm()),
                item.promptInstructions().strip(),
                item.studentLectureStyle()));
    }

    private static Path repoRoot(Path cwd) {
        Path current = cwd;
        for (int depth = 0; depth < 5 && current != null; depth += 1) {
            if (Files.isDirectory(current.resolve("文档"))
                    && Files.isDirectory(current.resolve("backend-java"))
                    && Files.isDirectory(current.resolve("frontend"))) {
                return current;
            }
            current = current.getParent();
        }
        return cwd;
    }

    private static List<String> safeList(List<String> values, List<String> fallback) {
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(8)
                .toList();
    }

    private static String defaultText(String value, String fallback) {
        return isBlank(value) ? fallback : value.strip();
    }

    private static String emptyToNull(String value) {
        return isBlank(value) ? null : value.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ConfiguredTemplateSkill(
            String templateCode,
            String displayName,
            String sourceType,
            String audience,
            String description,
            String category,
            String visualStyle,
            List<String> difficultyBands,
            List<String> tags,
            String referenceTitle,
            String referencePath,
            String referencePreview,
            String promptInstructions,
            Integer blankSpaceEm,
            Integer questionGapEm,
            boolean studentLectureStyle) {
    }
}
