package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teaching.vo.TeachingHandoutTemplateResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Discovers local PDF handout references and converts them into selectable teaching templates.
 */
final class TeachingHandoutLocalReferenceScanner {

    private static final int MAX_FILES = 24;
    private static final int MAX_DEPTH = 5;
    private static final int PREVIEW_LIMIT = 260;

    /**
     * Scans safe local reference roots. Large personal folders can be added through
     * MATH_AGENT_HANDOUT_TEMPLATE_DIRS with semicolon-separated absolute paths.
     */
    List<TeachingHandoutTemplateProfile> scan() {
        List<TeachingHandoutTemplateProfile> profiles = new ArrayList<>();
        for (Path root : referenceRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.find(
                    root,
                    MAX_DEPTH,
                    (path, attributes) -> attributes.isRegularFile() && isCandidatePdf(path))) {
                stream.sorted(Comparator
                        .comparingInt(TeachingHandoutLocalReferenceScanner::candidatePriority)
                        .thenComparing(path -> path.getFileName().toString()))
                        .limit(MAX_FILES)
                        .map(this::toTemplate)
                        .forEach(profiles::add);
            } catch (IOException ignored) {
                // A missing or locked reference folder should not break the teaching template API.
            }
        }
        return profiles.stream()
                .collect(java.util.stream.Collectors.toMap(
                        profile -> canonicalTitle(profile.summary().displayName()),
                        profile -> profile,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new))
                .values()
                .stream()
                .limit(MAX_FILES)
                .toList();
    }

    private List<Path> referenceRoots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path repoRoot = repoRoot(cwd);
        roots.add(repoRoot.resolve("文档").resolve("项目测试数据位置"));
        roots.addAll(defaultDesktopReferenceRoots());
        roots.add(repoRoot.getParent() == null
                ? repoRoot.resolve("workspace_data")
                : repoRoot.getParent().resolve("math_agent").resolve("workspace_data"));
        String configured = System.getenv("MATH_AGENT_HANDOUT_TEMPLATE_DIRS");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("math.agent.handout.template.dirs", "");
        }
        for (String item : configured.split(";")) {
            if (!item.isBlank()) {
                roots.add(Path.of(item.strip()).toAbsolutePath().normalize());
            }
        }
        return roots.stream().toList();
    }

    static List<Path> defaultDesktopReferenceRoots() {
        String userHome = System.getProperty("user.home", "");
        if (userHome == null || userHome.isBlank()) {
            return List.of();
        }
        Path desktop = Path.of(userHome).resolve("Desktop");
        return List.of(
                desktop.resolve("个人资料")
                        .resolve("初中数学")
                        .resolve("初中数学资料下载")
                        .resolve("02-专题讲义"),
                desktop.resolve("个人资料")
                        .resolve("初中数学")
                        .resolve("初中数学资料下载")
                        .resolve("03-综合复习与冲刺"),
                desktop.resolve("个人资料")
                        .resolve("初中数学")
                        .resolve("初中数学资料下载")
                        .resolve("07-地区试题"),
                Path.of(userHome)
                        .resolve("Documents")
                        .resolve("xwechat_files"));
    }

    private static Path repoRoot(Path cwd) {
        Path current = cwd;
        for (int depth = 0; depth < 4 && current != null; depth += 1) {
            if (Files.isDirectory(current.resolve("文档"))
                    && Files.isDirectory(current.resolve("backend-java"))
                    && Files.isDirectory(current.resolve("frontend"))) {
                return current;
            }
            current = current.getParent();
        }
        if ("backend-java".equalsIgnoreCase(cwd.getFileName() == null ? "" : cwd.getFileName().toString())
                && cwd.getParent() != null) {
            return cwd.getParent();
        }
        return cwd;
    }

    private TeachingHandoutTemplateProfile toTemplate(Path path) {
        String fileName = path.getFileName().toString();
        String title = fileName.replaceFirst("(?i)\\.pdf$", "");
        String preview = extractPreview(path);
        List<String> tags = tags(title, path);
        List<String> difficulty = difficultyBands(title, path);
        String audience = inferAudience(title);
        String visualStyle = inferVisualStyle(title);
        String prompt = """
                参考本机真实 PDF 讲义模板，不要照抄原文。
                参考文件：%s
                参考摘要：%s
                版式要求：
                1. 保留讲义感：页眉、讲次/专题标题、知识点、题型方法、例题、练习、总结、页脚；
                2. 题型训练要按基础、提高、压轴递进；
                3. 教师版写答案、板书步骤和追问；学生版保留提示、留白和练习；
                4. 颜色要克制：教师版可用深蓝/墨绿强调方法，学生版用浅色提示块；
                5. 输出要适合 PDF 打印，不要写模型名、token、后端诊断、文件路径。
                """.formatted(title, preview.isBlank() ? "未能抽取文字，按文件名和模板标签参考。" : preview);
        return new TeachingHandoutTemplateProfile(
                new TeachingHandoutTemplateResponse(
                        "local_pdf_" + stableCode(path),
                        title,
                        "local_reference",
                        audience,
                        "来自本机 PDF 参考讲义，生成时会把其结构摘要交给 AI。",
                        inferCategory(title),
                        visualStyle,
                        difficulty,
                        tags,
                        fileName,
                        path.toAbsolutePath().normalize().toString(),
                        preview),
                prompt,
                "student".equals(audience) || title.contains("学霸笔记"));
    }

    private static boolean isCandidatePdf(Path path) {
        String fileName = path.getFileName().toString();
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(".pdf")) {
            return false;
        }
        String title = fileName.replaceFirst("(?i)\\.pdf$", "").strip();
        if (title.isBlank() || title.equalsIgnoreCase("input") || title.equalsIgnoreCase("output")) {
            return false;
        }
        if ("参考讲义数学空间向量".equals(title)) {
            return false;
        }
        String fullPath = path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        return !fullPath.contains("\\tmp\\")
                && !fullPath.contains("/tmp/")
                && !fullPath.contains("\\temp\\")
                && !fullPath.contains("/temp/")
                && !fullPath.contains("\\target\\")
                && !fullPath.contains("/target/");
    }

    private static int candidatePriority(Path path) {
        String value = (path.getFileName() + " " + path).toLowerCase(Locale.ROOT);
        int score = 100;
        score -= containsAnyText(value, "反比例", "函数", "学霸笔记") ? 35 : 0;
        score -= containsAnyText(value, "讲义", "专题", "题型", "知识点") ? 25 : 0;
        score -= containsAnyText(value, "压轴", "高考", "中考", "综合") ? 15 : 0;
        score -= containsAnyText(value, "学生版", "教师版") ? 10 : 0;
        score += containsAnyText(value, "副本", "备用", "copy") ? 12 : 0;
        return score;
    }

    private static boolean containsAnyText(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String extractPreview(Path path) {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(2, document.getNumberOfPages()));
            return compact(stripper.getText(document), PREVIEW_LIMIT);
        } catch (IOException | RuntimeException exception) {
            return "";
        }
    }

    private static String stableCode(Path path) {
        String normalized = path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        return Integer.toUnsignedString(normalized.hashCode(), 36);
    }

    private static String canonicalTitle(String title) {
        return title == null ? "" : title
                .replaceFirst("^(ykm_\\d+_|网易_|有道_|新东方_)", "")
                .replaceAll("(_?副本\\d*|_?备用\\d*|_?主副本\\d*)$", "")
                .replaceAll("\\s+", " ")
                .strip()
                .toLowerCase(Locale.ROOT);
    }

    private static String inferAudience(String title) {
        if (title.contains("学生")) {
            return "student";
        }
        if (title.contains("教师") || title.contains("答案") || title.contains("详解")) {
            return "teacher";
        }
        return "mixed";
    }

    private static String inferCategory(String title) {
        if (title.contains("高考") || title.contains("压轴") || title.contains("题型")) {
            return "专题训练";
        }
        if (title.contains("教师") || title.contains("详解")) {
            return "教师详解";
        }
        if (title.contains("学生") || title.contains("学霸笔记")) {
            return "学生讲义";
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
        if (title.contains("高考") || title.contains("压轴")) {
            return "题型训练";
        }
        return "PDF参考";
    }

    private static List<String> difficultyBands(String title, Path path) {
        LinkedHashSet<String> bands = new LinkedHashSet<>();
        bands.add("基础");
        if (containsAny(title, path, "提高", "综合", "专题", "学霸")) {
            bands.add("提高");
        }
        if (containsAny(title, path, "压轴", "高考", "竞赛", "隐圆")) {
            bands.add("压轴");
        }
        return bands.stream().toList();
    }

    private static List<String> tags(String title, Path path) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        tags.add("本机参考");
        addIf(tags, title, "空间向量", "空间向量");
        addIf(tags, title, "向量", "向量");
        addIf(tags, title, "反比例", "反比例函数");
        addIf(tags, title, "函数", "函数");
        addIf(tags, title, "高考", "高考");
        addIf(tags, title, "压轴", "压轴");
        addIf(tags, title, "教师", "教师版");
        addIf(tags, title, "学生", "学生版");
        addIf(tags, title, "学霸笔记", "学霸笔记");
        if (tags.size() < 4) {
            String parent = path.getParent() == null ? "" : path.getParent().getFileName().toString();
            if (!parent.isBlank()) {
                tags.add(parent.length() > 10 ? parent.substring(0, 10) : parent);
            }
        }
        return tags.stream().limit(5).toList();
    }

    private static void addIf(Set<String> tags, String title, String needle, String tag) {
        if (title.contains(needle)) {
            tags.add(tag);
        }
    }

    private static boolean containsAny(String title, Path path, String... needles) {
        String value = title + " " + path;
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
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
}
