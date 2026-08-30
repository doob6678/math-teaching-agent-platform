package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.stereotype.Service;

/**
 * Reads authoritative teacher source files from the Docker-persisted source volume.
 *
 * <p>This component is intentionally independent of MySQL.  MySQL rows may still be used by the legacy sync
 * command that knows how to locate a source, but the MCP read path only consumes this file-backed catalog.  The
 * catalog stores an opaque document id, tenant, source root and checksum; it never becomes model-visible as a path.</p>
 */
@Service
public final class TeacherSourceFileReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Entry>> ENTRIES = new TypeReference<>() { };
    private static final List<String> TEXT_EXTENSIONS = List.of(".md", ".markdown", ".txt");

    private final Path catalogPath;
    private final TeacherSourceSyncProperties properties;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public TeacherSourceFileReader(TeacherSourceSyncProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.catalogPath = properties.feishuStagingRoot().resolve(".source-file-catalog.json").normalize();
    }

    /** Registers a validated Feishu source root before its vector index is published. */
    public void register(String tenantId, String documentId, Path root, String checksum) {
        String key = key(tenantId, documentId);
        Path normalizedRoot = properties.requireSourceRoot(root);
        Entry entry = new Entry(normalizedRoot.toString(), text(checksum));
        lock.writeLock().lock();
        try {
            Map<String, Entry> entries = load();
            entries.put(key, entry);
            persist(entries);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Removes a stale source registration after an archived or textless source is replaced. */
    public void unregister(String tenantId, String documentId) {
        String key = key(tenantId, documentId);
        lock.writeLock().lock();
        try {
            Map<String, Entry> entries = load();
            if (entries.remove(key) != null) {
                persist(entries);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Vector rows may outlive an operator-replaced volume. Search callers use this check before turning such a row
     * into model-visible evidence, while {@link #read(String, String)} retains the detailed fail-closed read contract.
     */
    public boolean isSourceAvailable(String tenantId, String documentId) {
        lock.readLock().lock();
        try {
            Entry entry = load().get(key(tenantId, documentId));
            if (entry == null) {
                return false;
            }
            Path root = properties.requireSourceRoot(Path.of(entry.root()));
            return Files.exists(root) && Files.isReadable(root) && !listTextFiles(root).isEmpty();
        } catch (IOException | RuntimeException exception) {
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads one registered FILE path with a character bound. The caller supplies the persisted FILE relative path;
     * this method never enumerates sibling files or exposes the backend source root.
     */
    public SourceFile readFile(
            String tenantId,
            String rootDocumentId,
            String relativeName,
            int maxCharacters) {
        lock.readLock().lock();
        try {
            Entry entry = load().get(key(tenantId, rootDocumentId));
            if (entry == null) {
                throw new IllegalArgumentException("Source file is not registered for this tenant/document");
            }
            Path root = properties.requireSourceRoot(Path.of(entry.root()));
            Path relative = Path.of(text(relativeName).replace('\\', '/')).normalize();
            if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")) {
                throw new IllegalArgumentException("Source file relative path is invalid");
            }
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || !isText(file)) {
                throw new IllegalStateException("Registered source file is unavailable");
            }
            int bound = Math.max(1, Math.min(maxCharacters, 1_000_000));
            String content;
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                StringBuilder value = new StringBuilder(Math.min(bound, 8192));
                char[] buffer = new char[Math.min(8192, bound)];
                int remaining = bound;
                while (remaining > 0) {
                    int read = reader.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (read < 0) {
                        break;
                    }
                    value.append(buffer, 0, read);
                    remaining -= read;
                }
                content = value.toString();
            }
            return new SourceFile(relative.toString().replace("\\", "/"), content);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read registered source file", exception);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Checks one registered FILE path without materializing the ROOT file catalog.
     */
    public boolean isFileAvailable(String tenantId, String rootDocumentId, String relativeName) {
        try {
            Entry entry = load().get(key(tenantId, rootDocumentId));
            if (entry == null) {
                return false;
            }
            Path root = properties.requireSourceRoot(Path.of(entry.root()));
            Path relative = Path.of(text(relativeName).replace('\\', '/')).normalize();
            Path file = root.resolve(relative).normalize();
            return !relative.isAbsolute() && !relative.startsWith("..") && file.startsWith(root)
                    && Files.isRegularFile(file) && isText(file);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Reads source text by opaque document id. This legacy compatibility method remains for management callers;
     * retrieval and MCP use readFile/readFilePage-style FILE APIs and never invoke this ROOT-wide catalog read.
     */
    public SourceDocument read(String tenantId, String documentId) {
        lock.readLock().lock();
        try {
            Entry entry = load().get(key(tenantId, documentId));
            if (entry == null) {
                throw new IllegalArgumentException("Source file is not registered for this tenant/document");
            }
            Path root;
            try {
                root = properties.requireSourceRoot(Path.of(entry.root()));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Registered source root is unavailable", exception);
            }
            if (!Files.exists(root)) {
                throw new IllegalStateException("Registered source root is unavailable");
            }
            List<SourceFile> files = new ArrayList<>();
            for (Path file : listTextFiles(root)) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                files.add(new SourceFile(root.relativize(file).toString().replace('\\', '/'), content));
            }
            if (files.isEmpty()) {
                throw new IllegalStateException("Registered source has no readable text files");
            }
            return new SourceDocument(text(documentId), text(entry.checksum()), List.copyOf(files));
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalArgumentException || exception instanceof IllegalStateException) {
                throw (RuntimeException) exception;
            }
            throw new IllegalStateException("Failed to read registered source files", exception);
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<Path> listTextFiles(Path root) throws IOException {
        if (Files.isRegularFile(root)) {
            return isText(root) ? List.of(root) : List.of();
        }
        Path realRoot = root.toRealPath();
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            return path.toRealPath().startsWith(realRoot) && isText(path);
                        } catch (IOException exception) {
                            return false;
                        }
                    })
                    .sorted(Comparator.comparing(Path::toString)).toList();
        }
    }

    private boolean isText(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return TEXT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private Map<String, Entry> load() {
        if (!Files.isRegularFile(catalogPath)) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(MAPPER.readValue(catalogPath.toFile(), ENTRIES));
        } catch (IOException exception) {
            throw new IllegalStateException("Source file catalog is invalid", exception);
        }
    }

    private void persist(Map<String, Entry> entries) {
        try {
            Files.createDirectories(catalogPath.getParent());
            Path temporary = catalogPath.resolveSibling(catalogPath.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), entries);
            Files.move(temporary, catalogPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist source file catalog", exception);
        }
    }

    private static String key(String tenantId, String documentId) {
        if (text(tenantId).isBlank() || text(documentId).isBlank()) {
            throw new IllegalArgumentException("tenantId and documentId are required");
        }
        return tenantId.strip() + "\u001f" + documentId.strip();
    }

    private static String text(String value) { return value == null ? "" : value.strip(); }

    private record Entry(String root, String checksum) { }

    public record SourceDocument(String documentId, String checksum, List<SourceFile> files) { }

    public record SourceFile(String relativeName, String text) { }
}
