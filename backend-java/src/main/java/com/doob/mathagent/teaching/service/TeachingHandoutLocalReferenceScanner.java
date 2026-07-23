package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.vo.TeachingHandoutTemplateResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Scans a very small set of local high-quality PDF references for the handout template shelf.
 *
 * <p>This scanner is intentionally strict. The frontend template shelf is a curated "bookshelf", not a dump of every
 * local exam paper. Loosening these filters easily reintroduces blank papers, answer sheets, OCR scraps, and other
 * noisy files that make the shelf unusable.</p>
 */
final class TeachingHandoutLocalReferenceScanner {

    private static final int MAX_FILES = 8;
    private static final int MAX_DEPTH = 7;
    private static final int PREVIEW_LIMIT = 260;
    private static final Set<String> STRONGLY_PREFERRED_NAME_PARTS = Set.of(
            "反比例函数",
            "空间向量",
            "双曲线",
            "圆锥曲线",
            "导数",
            "解析几何",
            "赵礼显",
            "zhao_lixian",
            "daoshu",
            "gaokao",
            "handout",
            "学霸笔记",
            "学生版",
            "教师版",
            "讲义",
            "专题");
    private static final Set<String> FORBIDDEN_NAME_PARTS = Set.of(
            "答案",
            "试卷",
            "真题",
            "月考",
            "期中",
            "期末",
            "模拟",
            "空白",
            "答题卡",
            "参考答案",
            "解析版",
            "OCR",
            "页码",
            "截图",
            "试题");

    List<TeachingHandoutTemplateProfile> scan() {
        LinkedHashMap<String, TeachingHandoutTemplateProfile> profiles = new LinkedHashMap<>();
        List<Path> candidates = collectCandidates(configuredReferenceRoots(), true);
        if (candidates.isEmpty()) {
            candidates = collectCandidates(defaultReferenceRoots(), false);
        }
        for (Path path : candidates) {
            TeachingHandoutTemplateProfile profile = toTemplate(path);
            if (profile == null) {
                continue;
            }
            profiles.putIfAbsent(canonicalTitle(profile.summary().displayName()), profile);
            if (profiles.size() >= MAX_FILES) {
                break;
            }
        }
        return List.copyOf(profiles.values());
    }

    private List<Path> collectCandidates(List<RootSpec> roots, boolean allowVolatilePaths) {
        LinkedHashMap<Path, Integer> candidates = new LinkedHashMap<>();
        for (RootSpec root : roots) {
            if (!Files.isDirectory(root.path())) {
                continue;
            }
            try (Stream<Path> stream = Files.find(root.path(), MAX_DEPTH, (path, attributes) ->
                    attributes.isRegularFile() && isCandidatePdf(path, allowVolatilePaths))) {
                stream.map(path -> path.toAbsolutePath().normalize())
                        .sorted(Comparator
                                .comparingInt((Path path) -> candidatePriority(path, root.priorityBias()))
                                .thenComparing((Path path) -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .limit(MAX_FILES * 4L)
                        .forEach(path -> candidates.merge(path, root.priorityBias(), Math::min));
            } catch (IOException ignored) {
                // Local reference folders are optional and should never break the teaching APIs.
            }
        }
        return candidates.entrySet().stream()
                .sorted(Comparator
                        .comparingInt((Map.Entry<Path, Integer> entry) -> candidatePriority(entry.getKey(), entry.getValue()))
                        .thenComparing((Map.Entry<Path, Integer> entry) -> entry.getKey().getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                .map(Map.Entry::getKey)
                .limit(MAX_FILES)
                .toList();
    }

    private List<RootSpec> defaultReferenceRoots() {
        LinkedHashMap<Path, RootSpec> roots = new LinkedHashMap<>();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path repoRoot = repoRoot(cwd);
        roots.put(repoRoot.resolve("文档").resolve("项目测试数据位置"), new RootSpec(repoRoot.resolve("文档").resolve("项目测试数据位置"), -10));
        // Evaluation reports and benchmark PDFs are test fixtures, not teaching references. Scanning them here
        // makes benchmark wording appear as a user-facing template and contaminates otherwise real handouts.
        for (Path root : defaultDesktopReferenceRoots()) {
            roots.putIfAbsent(root, new RootSpec(root, 0));
        }
        return roots.values().stream().toList();
    }

    private List<RootSpec> configuredReferenceRoots() {
        LinkedHashMap<Path, RootSpec> roots = new LinkedHashMap<>();
        addConfiguredRoots(roots, System.getenv("MATH_AGENT_HANDOUT_TEMPLATE_DIRS"), -40);
        addConfiguredRoots(roots, System.getProperty("math.agent.handout.template.dirs", ""), -40);
        return roots.values().stream().toList();
    }

    static List<Path> defaultDesktopReferenceRoots() {
        String userHome = System.getProperty("user.home", "");
        if (userHome == null || userHome.isBlank()) {
            return List.of();
        }
        Path desktop = Path.of(userHome).resolve("Desktop");
        Path documents = Path.of(userHome).resolve("Documents");
        return List.of(
                desktop.resolve("个人资料").resolve("初中数学").resolve("初中数学资料下载").resolve("02-专题讲义"),
                desktop.resolve("个人资料").resolve("初中数学").resolve("初中数学资料下载").resolve("03-综合复习与冲刺"),
                desktop.resolve("个人资料").resolve("初中数学").resolve("初中数学资料下载").resolve("07-地区试题"),
                desktop.resolve("个人资料").resolve("高中数学").resolve("latex画图"),
                desktop.resolve("个人资料").resolve("高中数学").resolve("feishu-doc-sync").resolve("data").resolve("documents_full").resolve("高中数学"),
                desktop.resolve("个人资料").resolve("高中数学").resolve("高中数学课本"),
                desktop.resolve("个人资料").resolve("高中数学").resolve("高考历年真题"),
                desktop.resolve("个人资料").resolve("高考真题"),
                documents.resolve("xwechat_files"));
    }

    private static void addConfiguredRoots(Map<Path, RootSpec> roots, String configured, int priorityBias) {
        if (configured == null || configured.isBlank()) {
            return;
        }
        for (String item : configured.split(";")) {
            if (!item.isBlank()) {
                Path path = Path.of(item.strip()).toAbsolutePath().normalize();
                roots.put(path, new RootSpec(path, priorityBias));
            }
        }
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

    private static boolean isCandidatePdf(Path path, boolean allowVolatilePaths) {
        String fileName = path.getFileName().toString();
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(".pdf")) {
            return false;
        }
        String title = fileName.replaceFirst("(?i)\\.pdf$", "").strip();
        if (title.isBlank() || looksGarbled(title)) {
            return false;
        }
        if (!allowVolatilePaths) {
            String fullPath = path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
            if (fullPath.contains("\\tmp\\")
                    || fullPath.contains("\\temp\\")
                    || fullPath.contains("\\target\\")
                    || fullPath.contains("/tmp/")
                    || fullPath.contains("/temp/")
                    || fullPath.contains("/target/")) {
                return false;
            }
        }
        for (String forbidden : FORBIDDEN_NAME_PARTS) {
            if (title.contains(forbidden)) {
                return false;
            }
        }
        String value = (title + " " + path).toLowerCase(Locale.ROOT);
        boolean looksLikeHandout = value.contains("讲义")
                || value.contains("专题")
                || value.contains("学生版")
                || value.contains("教师版")
                || value.contains("学霸笔记")
                || value.contains("handout");
        boolean hasTopicAnchor = value.contains("反比例")
                || value.contains("函数")
                || value.contains("双曲线")
                || value.contains("空间向量")
                || value.contains("导数")
                || value.contains("圆锥")
                || value.contains("解析几何")
                || value.contains("zhao_lixian")
                || value.contains("daoshu")
                || value.contains("gaokao");
        return candidatePriority(path, 0) < 0 && (looksLikeHandout || hasTopicAnchor) && !looksLikeFigureOnly(title);
    }

    private static int candidatePriority(Path path, int priorityBias) {
        String title = path.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
        String value = (title + " " + path).toLowerCase(Locale.ROOT);
        int score = priorityBias;
        for (String preferred : STRONGLY_PREFERRED_NAME_PARTS) {
            if (value.contains(preferred.toLowerCase(Locale.ROOT))) {
                score -= preferred.length() >= 4 ? 18 : 10;
            }
        }
        if (value.contains("教师") && value.contains("版")) {
            score -= 8;
        }
        if (value.contains("学生") && value.contains("版")) {
            score -= 8;
        }
        if (value.contains("pdf")) {
            score -= 2;
        }
        for (String forbidden : FORBIDDEN_NAME_PARTS) {
            if (value.contains(forbidden.toLowerCase(Locale.ROOT))) {
                score += 60;
            }
        }
        if (value.contains("初中") || value.contains("中考")) {
            score += 20;
        }
        if (value.contains("课本") || value.contains("教材")) {
            score += 18;
        }
        return score;
    }

    private TeachingHandoutTemplateProfile toTemplate(Path path) {
        String fileName = path.getFileName().toString();
        String title = fileName.replaceFirst("(?i)\\.pdf$", "");
        String preview = extractPreview(path);
        if (looksBadPreview(preview)) {
            return null;
        }
        List<String> tags = tags(title, path);
        List<String> difficulty = difficultyBands(title, path);
        String audience = inferAudience(title);
        String visualStyle = inferVisualStyle(title);
        String prompt = """
                参考本机真实数学讲义 PDF 的结构与表达，不要照抄原文。
                参考标题：%s
                参考摘要：%s
                生成要求：
                1. 保留真实讲义感：专题标题、知识点、方法卡、例题、练习、总结；
                2. 题型训练按 A 基础、B 提高、C 压轴递进，能匹配时优先使用真实题库题目；
                3. 教师版写来源、方法步骤、板书顺序、答案与评分点；
                4. 学生版只写提示、题目和干净空白，不泄露答案，空白处不要标“作答区”“手写区”“留白区”；
                5. 所有内容必须紧扣当前主题，不能被无关资料带偏；
                6. 页面版式由系统渲染负责，正文不要写模型、token、调试信息、文件路径或任何版式元信息。
                """.formatted(title, preview.isBlank() ? "未抽取到稳定正文，按标题和标签参考。" : preview);
        return new TeachingHandoutTemplateProfile(
                new TeachingHandoutTemplateResponse(
                        "local_pdf_" + stableCode(path),
                        title,
                        "local_reference",
                        audience,
                        "来自本机精选 PDF 讲义参考，前端书架仅展示少量高质量样本。",
                        inferCategory(title),
                        visualStyle,
                        difficulty,
                        tags,
                        fileName,
                        path.toAbsolutePath().normalize().toString(),
                        preview,
                        inferredBlankSpaceEm(audience, title),
                        inferredQuestionGapEm(audience, title)),
                prompt,
                "student".equals(audience) || title.contains("学霸笔记"));
    }

    private static int inferredBlankSpaceEm(String audience, String title) {
        if ("student".equals(audience) || title.contains("学霸笔记") || title.contains("学生")) {
            return 8;
        }
        if (title.contains("高考") || title.contains("压轴")) {
            return 7;
        }
        return 6;
    }

    private static int inferredQuestionGapEm(String audience, String title) {
        if (title.contains("压轴")) {
            return 5;
        }
        return "student".equals(audience) ? 4 : 3;
    }

    private String extractPreview(Path path) {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(2, document.getNumberOfPages()));
            String preview = compact(stripper.getText(document), PREVIEW_LIMIT);
            return looksGarbled(preview) ? "" : preview;
        } catch (IOException | RuntimeException exception) {
            return "";
        }
    }

    private static boolean looksBadPreview(String preview) {
        if (preview == null || preview.isBlank()) {
            return false;
        }
        String normalized = preview.replaceAll("\\s+", "");
        if (normalized.length() < 12) {
            return true;
        }
        int symbolCount = 0;
        for (int index = 0; index < normalized.length(); index += 1) {
            char ch = normalized.charAt(index);
            if (!Character.isLetterOrDigit(ch) && !Character.isIdeographic(ch)) {
                symbolCount += 1;
            }
        }
        return symbolCount * 2 > normalized.length();
    }

    private static String stableCode(Path path) {
        String normalized = path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        return Integer.toUnsignedString(normalized.hashCode(), 36);
    }

    private static String canonicalTitle(String title) {
        return title == null ? "" : title
                .replaceAll("(_?副本\\d*|_?备用\\d*|_?主副本\\d*)$", "")
                .replaceAll("\\s+", " ")
                .strip()
                .toLowerCase(Locale.ROOT);
    }

    private static String inferAudience(String title) {
        if (title.contains("学生")) {
            return "student";
        }
        if (title.contains("教师") || title.contains("详解")) {
            return "teacher";
        }
        return "mixed";
    }

    private static String inferCategory(String title) {
        if (title.contains("教师")) {
            return "教师详解";
        }
        if (title.contains("学生") || title.contains("学霸笔记")) {
            return "学生讲义";
        }
        if (title.contains("高考") || title.contains("专题")) {
            return "专题训练";
        }
        return "本机参考";
    }

    private static String inferVisualStyle(String title) {
        if (title.contains("学霸笔记")) {
            return "学霸笔记";
        }
        if (title.contains("教师")) {
            return "教案式";
        }
        return "PDF参考";
    }

    private static List<String> difficultyBands(String title, Path path) {
        LinkedHashSet<String> bands = new LinkedHashSet<>();
        bands.add("基础");
        String value = title + " " + path;
        if (value.contains("提高") || value.contains("专题") || value.contains("学霸")) {
            bands.add("提高");
        }
        if (value.contains("压轴") || value.contains("高考")) {
            bands.add("压轴");
        }
        return bands.stream().toList();
    }

    private static List<String> tags(String title, Path path) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String lower = (title + " " + path).toLowerCase(Locale.ROOT);
        values.add("本机参考");
        addIf(values, title, "反比例", "反比例函数");
        addIf(values, title, "双曲线", "双曲线");
        addIf(values, title, "空间向量", "空间向量");
        addIf(values, title, "导数", "导数");
        addIf(values, title, "圆锥", "圆锥曲线");
        addIf(values, title, "解析几何", "解析几何");
        addIf(values, title, "赵礼显", "赵礼显");
        addIf(values, title, "教师", "教师版");
        addIf(values, title, "学生", "学生版");
        if (lower.contains("zhao_lixian")) {
            values.add("赵礼显");
        }
        if (lower.contains("daoshu")) {
            values.add("导数");
        }
        if (lower.contains("gaokao")) {
            values.add("高考");
        }
        if (values.size() < 4 && path.getParent() != null) {
            String parent = path.getParent().getFileName() == null ? "" : path.getParent().getFileName().toString();
            if (!parent.isBlank() && !looksGarbled(parent)) {
                values.add(parent.length() > 10 ? parent.substring(0, 10) : parent);
            }
        }
        return values.stream().limit(5).toList();
    }

    private static void addIf(Set<String> tags, String title, String needle, String tag) {
        if (title.contains(needle)) {
            tags.add(tag);
        }
    }

    private static String compact(String value, int limit) {
        String text = value == null ? "" : value
                .replaceAll("\\s+", " ")
                .replaceAll("[\\u0000-\\u001f]", " ")
                .strip();
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "...";
    }

    private static boolean looksGarbled(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.strip().toLowerCase(Locale.ROOT);
        return lower.contains("ã")
                || lower.contains("â")
                || lower.contains("ä¸")
                || lower.contains("å")
                || lower.contains("æ")
                || lower.contains("ç");
    }

    private static boolean looksLikeFigureOnly(String title) {
        String normalized = title.toLowerCase(Locale.ROOT);
        return normalized.contains("示意图")
                || normalized.contains("图")
                || normalized.matches(".*\\bpef\\b.*")
                || normalized.matches("^[a-z0-9_\\-]{1,10}$");
    }

    private record RootSpec(Path path, int priorityBias) {
    }
}
