package com.doob.mathagent.retrieval;

import com.doob.mathagent.resources.TextbookCatalogItem;
import com.doob.mathagent.resources.TextbookCatalogReader;
import com.doob.mathagent.resources.TextbookChunk;
import com.doob.mathagent.resources.TextbookChunkReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class TextbookRetrievalService {

    private static final long LOAD_FAILURE_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final TextbookCatalogReader catalogReader;
    private final TextbookChunkReader chunkReader;
    private final LocalTextbookBm25SearchEngine searchEngine;
    private final RetrievalAuditSink auditSink;
    /**
     * 语料加载互斥锁：防止缓存未命中时多个请求同时读取大批量教材文件，降低缓存击穿风险。
     */
    private final Object corpusLoadLock = new Object();
    /**
     * 最近一次成功加载的教材语料快照：源文件临时异常时可作为旧缓存兜底，降低缓存雪崩影响。
     */
    private volatile CachedTextbookCorpus cachedCorpus;
    /**
     * 最近一次语料加载失败状态：用于失败冷却，避免底层文件或存储异常时每个请求都重复触发昂贵加载。
     */
    private volatile CorpusLoadFailure lastLoadFailure;

    public TextbookRetrievalService(
            TextbookCatalogReader catalogReader,
            TextbookChunkReader chunkReader,
            LocalTextbookBm25SearchEngine searchEngine,
            RetrievalAuditSink auditSink) {
        this.catalogReader = catalogReader;
        this.chunkReader = chunkReader;
        this.searchEngine = searchEngine;
        this.auditSink = auditSink;
    }

    /**
     * 使用默认教材检索上下文执行搜索，主要供单元测试和内部调用使用。
     */
    public TextbookSearchResponse search(Path processedBooksRoot, TextbookSearchRequest request) {
        return search(processedBooksRoot, request, RetrievalRequestContext.defaultTextbookSearch());
    }

    /**
     * 执行 BM25-first 教材检索，并同步写入检索审计事件。
     */
    public TextbookSearchResponse search(
            Path processedBooksRoot,
            TextbookSearchRequest request,
            RetrievalRequestContext requestContext) {
        String queryId = UUID.randomUUID().toString();
        long startedAtNanos = System.nanoTime();
        Path normalizedRoot = processedBooksRoot.toAbsolutePath().normalize();
        List<TextbookChunk> chunks = loadCorpus(normalizedRoot).chunks();
        List<TextbookSearchHit> hits = searchEngine.search(request.query(), chunks, request.limit());
        int elapsedMs = elapsedMs(startedAtNanos);
        TextbookSearchResponse response = new TextbookSearchResponse(
                queryId,
                request.query(),
                request.limit(),
                "local_bm25_first",
                hits.size(),
                hits);
        auditSink.record(RetrievalAuditEvent.from(queryId, request, response, elapsedMs, requestContext));
        return response;
    }

    /**
     * 加载教材语料缓存；当多个请求同时缓存未命中时，只允许一个线程执行实际加载。
     */
    private CachedTextbookCorpus loadCorpus(Path processedBooksRoot) {
        try {
            return loadCorpusOrThrow(processedBooksRoot);
        } catch (RuntimeException e) {
            return recoverFromLoadFailure(processedBooksRoot, e);
        }
    }

    /**
     * 执行真实语料加载流程：先校验失败冷却，再计算源文件签名，最后在锁内单飞加载。
     */
    private CachedTextbookCorpus loadCorpusOrThrow(Path processedBooksRoot) {
        rejectDuringFailureCooldown(processedBooksRoot);
        List<TextbookCatalogItem> books = catalogReader.read(processedBooksRoot.resolve("catalog.jsonl"));
        List<Path> chunkPaths = books.stream()
                .map(book -> chunksPath(processedBooksRoot, book))
                .toList();
        List<SourceFileSignature> signatures = sourceSignatures(processedBooksRoot.resolve("catalog.jsonl"), chunkPaths);
        CachedTextbookCorpus snapshot = cachedCorpus;
        if (isCurrentSnapshot(snapshot, processedBooksRoot, signatures)) {
            return snapshot;
        }

        synchronized (corpusLoadLock) {
            rejectDuringFailureCooldown(processedBooksRoot);
            snapshot = cachedCorpus;
            if (isCurrentSnapshot(snapshot, processedBooksRoot, signatures)) {
                return snapshot;
            }
            CachedTextbookCorpus loaded = readCorpus(processedBooksRoot, signatures, chunkPaths);
            cachedCorpus = loaded;
            lastLoadFailure = null;
            return loaded;
        }
    }

    /**
     * 判断缓存快照是否仍对应当前根目录和源文件签名。
     */
    private static boolean isCurrentSnapshot(
            CachedTextbookCorpus snapshot,
            Path processedBooksRoot,
            List<SourceFileSignature> signatures) {
        return snapshot != null
                && snapshot.processedBooksRoot().equals(processedBooksRoot)
                && snapshot.signatures().equals(signatures);
    }

    /**
     * 加载失败后的恢复策略：同根目录旧缓存优先返回；没有旧缓存时记录失败并抛出可观测异常。
     */
    private CachedTextbookCorpus recoverFromLoadFailure(Path processedBooksRoot, RuntimeException failure) {
        CachedTextbookCorpus snapshot = cachedCorpus;
        lastLoadFailure = new CorpusLoadFailure(processedBooksRoot, System.nanoTime(), failure.getMessage());
        if (snapshot != null && snapshot.processedBooksRoot().equals(processedBooksRoot)) {
            return snapshot;
        }
        throw new IllegalStateException("Failed to load textbook corpus: " + failure.getMessage(), failure);
    }

    /**
     * 冷却期内阻断重复加载：无旧缓存时快速失败，有旧缓存时由上层恢复逻辑返回旧快照。
     */
    private void rejectDuringFailureCooldown(Path processedBooksRoot) {
        CorpusLoadFailure failure = lastLoadFailure;
        if (failure == null || !failure.processedBooksRoot().equals(processedBooksRoot)) {
            return;
        }
        long elapsedNanos = System.nanoTime() - failure.failedAtNanos();
        if (elapsedNanos < LOAD_FAILURE_COOLDOWN_NANOS) {
            throw new IllegalStateException("Textbook corpus load cooldown active after failure: " + failure.reason());
        }
    }

    /**
     * 从 chunk 文件读取完整教材语料，并封装为不可变缓存快照。
     */
    private CachedTextbookCorpus readCorpus(
            Path processedBooksRoot,
            List<SourceFileSignature> signatures,
            List<Path> chunkPaths) {
        List<TextbookChunk> chunks = new ArrayList<>();
        for (Path chunksPath : chunkPaths) {
            chunks.addAll(chunkReader.read(chunksPath));
        }
        CachedTextbookCorpus loaded = new CachedTextbookCorpus(processedBooksRoot, signatures, List.copyOf(chunks));
        return loaded;
    }

    /**
     * 计算 catalog 和 chunk 文件签名，用于判断缓存是否仍然有效。
     */
    private static List<SourceFileSignature> sourceSignatures(Path catalogPath, List<Path> chunkPaths) {
        List<SourceFileSignature> signatures = new ArrayList<>();
        signatures.add(sourceSignature(catalogPath));
        chunkPaths.stream().map(TextbookRetrievalService::sourceSignature).forEach(signatures::add);
        return List.copyOf(signatures);
    }

    /**
     * 读取单个源文件的大小和最后修改时间作为缓存签名。
     */
    private static SourceFileSignature sourceSignature(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return new SourceFileSignature(
                    normalized,
                    Files.size(normalized),
                    Files.getLastModifiedTime(normalized).to(TimeUnit.NANOSECONDS));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fingerprint textbook source file: " + path, e);
        }
    }

    /**
     * 根据 catalog 中的 book_root 找到优先使用的 AI chunk 文件。
     */
    private static Path chunksPath(Path processedBooksRoot, TextbookCatalogItem book) {
        Path bookRoot = Paths.get(book.bookRoot());
        if (!bookRoot.isAbsolute()) {
            bookRoot = processedBooksRoot.resolve(bookRoot);
        }
        Path aiChunks = bookRoot.resolve("jsonl_ai/chunks.jsonl");
        if (Files.exists(aiChunks)) {
            return aiChunks;
        }
        Path textChunks = bookRoot.resolve("jsonl/chunks.jsonl");
        if (Files.exists(textChunks)) {
            return textChunks;
        }
        throw new IllegalStateException("Missing textbook chunks for book " + book.docId() + ": " + bookRoot);
    }

    /**
     * 把纳秒耗时转换为毫秒，并避免整数溢出。
     */
    private static int elapsedMs(long startedAtNanos) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

    private record SourceFileSignature(Path path, long size, long lastModifiedNanos) {
    }

    /**
     * 教材语料加载失败状态。
     *
     * @param processedBooksRoot 失败对应的教材处理产物根目录。
     * @param failedAtNanos 使用 System.nanoTime 记录的失败时间，用于计算冷却窗口。
     * @param reason 最近一次失败原因，便于日志、测试和接口错误排查。
     */
    private record CorpusLoadFailure(Path processedBooksRoot, long failedAtNanos, String reason) {
    }

    private record CachedTextbookCorpus(
            Path processedBooksRoot,
            List<SourceFileSignature> signatures,
            List<TextbookChunk> chunks) {
    }
}
