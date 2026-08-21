package com.doob.mathagent.retrieval;

import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Reads only hash-bound canonical math-paper Markdown after Java has authorized an opaque document reference.
 *
 * <p>All corpus paths, filenames, and manifest locations remain internal. Question reads derive a manifest-owned
 * relative Markdown entry from a persisted numeric selector; callers cannot name files or paths.</p>
 */
@Service
public class CanonicalMathPaperAuthorizedBlockReader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID URL_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    private final Path corpusRoot;

    public CanonicalMathPaperAuthorizedBlockReader(
            @Value("${math-agent.teaching.canonical-paper.corpus-root:/app/data/math-paper-corpus}") Path corpusRoot) {
        this.corpusRoot = corpusRoot.toAbsolutePath().normalize();
    }

    /** Returns true only when the opaque reference resolves to a complete, hash-bound canonical publication. */
    public boolean isAvailable(String opaqueDocumentRef) {
        return resolve(opaqueDocumentRef) != null;
    }

    /** Returns original page Markdown blocks in source order for one manifest-authorized canonical paper. */
    public List<TeacherDocumentBlockResponse> read(String opaqueDocumentRef) {
        PublishedPaper paper = requirePublished(opaqueDocumentRef);
        return pageBlocks(opaqueDocumentRef, readVerified(paper.documentMarkdown(), paper.documentMarkdownSha256()));
    }

    /**
     * Reads exactly one manifest-indexed question Markdown file. The selector is never a path and must be a numeric
     * question number previously stored on the run-authorized evidence row.
     */
    public List<TeacherDocumentBlockResponse> readQuestion(String opaqueDocumentRef, String questionNumber) {
        PublishedPaper paper = requirePublished(opaqueDocumentRef);
        if (questionNumber == null || !questionNumber.matches("[1-9]\\d{0,2}")) {
            throw new IllegalArgumentException("Authorized canonical question is unavailable");
        }
        JsonNode question = paper.questions().stream()
                .filter(candidate -> questionNumber.equals(candidate.path("questionNumber").asText("")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Authorized canonical question is unavailable"));
        String relativeMarkdown = question.path("questionMarkdown").asText("");
        String markdownHash = question.path("questionMarkdownSha256").asText("");
        if (!relativeMarkdown.matches("questions/q-[0-9]{3}\\.md") || !markdownHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("Authorized canonical question is unavailable");
        }
        Path questionMarkdown = paper.root().resolve(relativeMarkdown).normalize();
        if (!questionMarkdown.startsWith(paper.root())) {
            throw new IllegalStateException("Authorized canonical question is unavailable");
        }
        String text = readVerified(questionMarkdown, markdownHash);
        int pageNo = question.path("sourcePages").isArray() && !question.path("sourcePages").isEmpty()
                ? question.path("sourcePages").get(0).asInt(0) : 0;
        return List.of(new TeacherDocumentBlockResponse(
                "question-" + questionNumber, opaqueDocumentRef, "question-" + questionNumber,
                "canonical_question_markdown", 1, "", "第 " + questionNumber + " 题", pageNo > 0 ? pageNo : null,
                "", "", "reference", text, text, "[]", "[]", "[]", "[]", "", 1.0d, "active"));
    }

    private PublishedPaper requirePublished(String opaqueDocumentRef) {
        PublishedPaper paper = resolve(opaqueDocumentRef);
        if (paper == null) {
            throw new IllegalArgumentException("Authorized canonical source is unavailable");
        }
        return paper;
    }

    private PublishedPaper resolve(String opaqueDocumentRef) {
        if (opaqueDocumentRef == null || opaqueDocumentRef.isBlank() || !Files.isDirectory(corpusRoot)) {
            return null;
        }
        try (Stream<Path> roots = Files.list(corpusRoot)) {
            return roots.filter(Files::isDirectory)
                    .map(this::publishedPaper)
                    .filter(paper -> paper != null && opaqueDocumentRef.equals(paper.documentRef()))
                    .findFirst()
                    .orElse(null);
        } catch (IOException exception) {
            return null;
        }
    }

    private PublishedPaper publishedPaper(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path manifest = normalizedRoot.resolve("source-manifest.json").normalize();
        Path document = normalizedRoot.resolve("document.md").normalize();
        if (!normalizedRoot.startsWith(corpusRoot) || !manifest.startsWith(normalizedRoot) || !document.startsWith(normalizedRoot)
                || !Files.isRegularFile(manifest) || !Files.isRegularFile(document)) {
            return null;
        }
        try {
            JsonNode source = JSON.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
            String name = source.path("documentFullName").asText("").strip();
            String hash = source.path("sourceSha256").asText("").strip();
            String markdown = source.path("documentMarkdown").asText("").strip();
            String markdownHash = source.path("documentMarkdownSha256").asText("").strip();
            JsonNode questions = source.path("questions");
            if (name.isBlank() || !hash.matches("[0-9a-fA-F]{64}") || !name.equals(normalizedRoot.getFileName().toString())
                    || !"document.md".equals(markdown) || !markdownHash.matches("[0-9a-fA-F]{64}")
                    || !questions.isArray() || source.path("questionCount").asInt(-1) != questions.size()
                    || !sha256(document).equalsIgnoreCase(markdownHash)) {
                return null;
            }
            List<JsonNode> index = new ArrayList<>();
            for (JsonNode question : questions) {
                String number = question.path("questionNumber").asText("");
                String questionMarkdown = question.path("questionMarkdown").asText("");
                String questionHash = question.path("questionMarkdownSha256").asText("");
                Path resolvedQuestion = normalizedRoot.resolve(questionMarkdown).normalize();
                if (!number.matches("[1-9]\\d{0,2}") || !questionMarkdown.matches("questions/q-[0-9]{3}\\.md")
                        || !questionHash.matches("[0-9a-fA-F]{64}") || !resolvedQuestion.startsWith(normalizedRoot)
                        || !Files.isRegularFile(resolvedQuestion) || !sha256(resolvedQuestion).equalsIgnoreCase(questionHash)) {
                    return null;
                }
                index.add(question);
            }
            return new PublishedPaper(uuid5(name + "\n" + hash).toString(), normalizedRoot, document, markdownHash, List.copyOf(index));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readVerified(Path path, String expectedSha256) {
        try {
            if (!Files.isRegularFile(path) || !sha256(path).equalsIgnoreCase(expectedSha256)) {
                throw new IllegalStateException("Authorized canonical source cannot be read");
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Authorized canonical source cannot be read", exception);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static List<TeacherDocumentBlockResponse> pageBlocks(String documentRef, String markdown) {
        String[] sections = markdown.split("(?m)(?=^##\\s+第\\s*\\d+\\s*页)");
        List<TeacherDocumentBlockResponse> blocks = new ArrayList<>();
        int order = 0;
        for (String section : sections) {
            String text = section.strip();
            if (text.isBlank()) {
                continue;
            }
            int pageNo = pageNumber(text);
            blocks.add(new TeacherDocumentBlockResponse(
                    "page-" + (++order), documentRef, "page-" + order, "canonical_markdown", order,
                    "", pageNo > 0 ? "第 " + pageNo + " 页" : "", pageNo > 0 ? pageNo : null, "", "", "reference",
                    text, text, "[]", "[]", "[]", "[]", "", 1.0d, "active"));
        }
        return List.copyOf(blocks);
    }

    private static int pageNumber(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^##\\s+第\\s*(\\d+)\\s*页").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static UUID uuid5(String value) {
        try {
            byte[] namespace = ByteBuffer.allocate(16)
                    .putLong(URL_NAMESPACE.getMostSignificantBits())
                    .putLong(URL_NAMESPACE.getLeastSignificantBits())
                    .array();
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(namespace);
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            return new UUID(ByteBuffer.wrap(hash, 0, 8).getLong(), ByteBuffer.wrap(hash, 8, 8).getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }

    private record PublishedPaper(
            String documentRef, Path root, Path documentMarkdown, String documentMarkdownSha256, List<JsonNode> questions) {
    }
}
