package com.doob.mathagent.teacher.block;

import com.doob.mathagent.teacher.block.entity.TeacherDocumentBlockEntity;
import com.doob.mathagent.teacher.block.mapper.TeacherDocumentBlockMapper;
import com.doob.mathagent.teacher.search.TeacherResourceSearchFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.springframework.stereotype.Component;

/**
 * Embedded Lucene BM25 route for teacher Feishu FILE blocks.
 *
 * <p>The index is an in-process read snapshot. It is populated only from the mapper's already-authorized SQL
 * snapshot, and every hit is revalidated by SQL before it is returned. Lucene therefore supplies mature TF/DF and
 * document-length normalization without becoming an authorization or source-of-truth store.</p>
 */
@Component
public final class TeacherResourceBm25SearchEngine implements AutoCloseable {

    private static final String CONTENT_FIELD = "content";
    private static final String BLOCK_ID_FIELD = "block_id";
    private static final int INDEX_PAGE_SIZE = 256;
    private static final int MAX_RETURNED_BLOCKS = 128;
    private static final long SNAPSHOT_TTL_MILLIS = 30_000L;

    private final TeacherDocumentBlockMapper mapper;
    private final Map<IndexKey, IndexSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Object snapshotLock = new Object();

    public TeacherResourceBm25SearchEngine(TeacherDocumentBlockMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Searches a viewer-specific BM25 snapshot and revalidates the resulting block ids through the current SQL policy.
     */
    public List<TeacherDocumentBlockEntity> search(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            List<String> terms,
            int fileLimit) {
        return search(tenantId, viewerRole, viewerSubjectId, terms, fileLimit, TeacherResourceSearchFilter.EMPTY);
    }

    /** Searches a filtered viewer snapshot; filter dimensions are part of the snapshot identity and SQL boundary. */
    public List<TeacherDocumentBlockEntity> search(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            List<String> terms,
            int fileLimit,
            TeacherResourceSearchFilter filter) {
        List<String> normalizedTerms = normalizedTerms(terms);
        if (tenantId == null || tenantId.isBlank() || normalizedTerms.isEmpty() || fileLimit <= 0) {
            return List.of();
        }
        TeacherResourceSearchFilter normalizedFilter = filter == null ? TeacherResourceSearchFilter.EMPTY : filter;
        IndexKey key = new IndexKey(
                tenantId.strip(),
                normalize(viewerRole),
                viewerSubjectId == null ? "" : viewerSubjectId.strip(),
                normalizedFilter.documentIds(),
                normalizedFilter.permissionScopes());
        IndexSnapshot snapshot;
        synchronized (snapshotLock) {
            snapshot = snapshots.get(key);
            if (snapshot == null || snapshot.expired()) {
                if (snapshot != null) {
                    snapshot.close();
                }
                snapshot = buildSnapshot(key);
                snapshots.put(key, snapshot);
            }
            List<Long> blockIds = snapshot.search(normalizedTerms, fileLimit);
            if (blockIds.isEmpty()) {
                return List.of();
            }
            List<TeacherDocumentBlockEntity> visible = mapper.selectSearchableFileBlocksByIds(
                    key.tenantId(), key.viewerRole(), key.viewerSubjectId(), key.documentIds(), key.permissionScopes(), blockIds,
                    Math.min(MAX_RETURNED_BLOCKS, blockIds.size()));
            Map<Long, TeacherDocumentBlockEntity> byId = new HashMap<>();
            for (TeacherDocumentBlockEntity entity : visible) {
                if (entity.getId() != null) {
                    byId.put(entity.getId(), entity);
                }
            }
            List<TeacherDocumentBlockEntity> ordered = new ArrayList<>();
            for (Long blockId : blockIds) {
                TeacherDocumentBlockEntity entity = byId.get(blockId);
                if (entity != null) {
                    ordered.add(entity);
                }
            }
            return List.copyOf(ordered);
        }
    }

    /** Invalidates all viewer snapshots for a tenant after a FILE replacement or purge. */
    public void invalidateTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        snapshots.entrySet().removeIf(entry -> {
            if (!tenantId.strip().equals(entry.getKey().tenantId())) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
    }

    /** Invalidates all snapshots because a caller changed the indexed corpus outside the block store. */
    public void invalidateAll() {
        snapshots.values().forEach(IndexSnapshot::close);
        snapshots.clear();
    }

    @Override
    public void close() {
        for (IndexSnapshot snapshot : snapshots.values()) {
            snapshot.close();
        }
        snapshots.clear();
    }

    private IndexSnapshot buildSnapshot(IndexKey key) {
        List<TeacherDocumentBlockEntity> blocks = new ArrayList<>();
        long afterBlockId = 0L;
        while (true) {
            List<TeacherDocumentBlockEntity> page = mapper.selectSearchableFileBlocksForBm25Index(
                    key.tenantId(), key.viewerRole(), key.viewerSubjectId(), key.documentIds(), key.permissionScopes(),
                    afterBlockId, INDEX_PAGE_SIZE);
            if (page == null || page.isEmpty()) {
                break;
            }
            blocks.addAll(page);
            long nextAfter = page.stream()
                    .map(TeacherDocumentBlockEntity::getId)
                    .filter(id -> id != null)
                    .max(Comparator.naturalOrder())
                    .orElse(afterBlockId);
            if (nextAfter <= afterBlockId || page.size() < INDEX_PAGE_SIZE) {
                break;
            }
            afterBlockId = nextAfter;
        }
        return IndexSnapshot.build(blocks);
    }

    private static List<String> normalizedTerms(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        return terms.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private record IndexKey(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            List<String> documentIds,
            List<String> permissionScopes) {
        private IndexKey {
            documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
            permissionScopes = permissionScopes == null ? List.of() : List.copyOf(permissionScopes);
        }
    }

    private static final class IndexSnapshot implements AutoCloseable {
        private final Analyzer analyzer;
        private final Directory directory;
        private final DirectoryReader reader;
        private final IndexSearcher searcher;
        private final long builtAtMillis;

        private IndexSnapshot(
                Analyzer analyzer,
                Directory directory,
                DirectoryReader reader,
                IndexSearcher searcher) {
            this.analyzer = analyzer;
            this.directory = directory;
            this.reader = reader;
            this.searcher = searcher;
            this.builtAtMillis = System.currentTimeMillis();
        }

        static IndexSnapshot build(List<TeacherDocumentBlockEntity> blocks) {
            Analyzer analyzer = new CJKAnalyzer();
            Directory directory = new ByteBuffersDirectory();
            try (IndexWriter writer = new IndexWriter(
                    directory,
                    new IndexWriterConfig(analyzer)
                            .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                            .setSimilarity(new BM25Similarity()))) {
                for (TeacherDocumentBlockEntity block : blocks == null ? List.<TeacherDocumentBlockEntity>of() : blocks) {
                    if (block == null || block.getId() == null || block.getStatus() == null
                            || !"active".equalsIgnoreCase(block.getStatus())) {
                        continue;
                    }
                    String content = searchableContent(block);
                    if (content.isBlank()) {
                        continue;
                    }
                    Document document = new Document();
                    document.add(new StringField(BLOCK_ID_FIELD, String.valueOf(block.getId()), Field.Store.YES));
                    document.add(new TextField(CONTENT_FIELD, content, Field.Store.NO));
                    writer.addDocument(document);
                }
                writer.commit();
                DirectoryReader reader = DirectoryReader.open(directory);
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.setSimilarity(new BM25Similarity());
                return new IndexSnapshot(analyzer, directory, reader, searcher);
            } catch (IOException exception) {
                try {
                    directory.close();
                } catch (IOException ignored) {
                    // Preserve the original index-build failure.
                }
                analyzer.close();
                throw new IllegalStateException("Failed to build teacher BM25 index", exception);
            }
        }

        List<Long> search(List<String> terms, int fileLimit) {
            int maxResults = Math.min(MAX_RETURNED_BLOCKS, Math.max(1, fileLimit) * 4);
            String queryText = String.join(" ", terms);
            try {
                Query query = new QueryParser(CONTENT_FIELD, analyzer).parse(QueryParser.escape(queryText));
                TopDocs topDocs = searcher.search(query, maxResults);
                List<Long> result = new ArrayList<>();
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    String blockId = searcher.doc(scoreDoc.doc).get(BLOCK_ID_FIELD);
                    if (blockId == null || blockId.isBlank()) {
                        continue;
                    }
                    try {
                        result.add(Long.valueOf(blockId));
                    } catch (NumberFormatException ignored) {
                        // A malformed persisted id cannot become a searchable evidence row.
                    }
                }
                return Collections.unmodifiableList(result);
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to execute teacher BM25 query", exception);
            }
        }

        boolean expired() {
            return System.currentTimeMillis() - builtAtMillis >= SNAPSHOT_TTL_MILLIS;
        }

        private static String searchableContent(TeacherDocumentBlockEntity block) {
            return String.join(
                    " ",
                    value(block.getChapter()),
                    value(block.getSection()),
                    value(block.getSourcePath()),
                    value(block.getBlockRole()),
                    value(block.getGraphTagNamesJson()),
                    value(block.getRawText()),
                    value(block.getNormalizedText()));
        }

        private static String value(String value) {
            return value == null ? "" : value;
        }

        @Override
        public void close() {
            try {
                reader.close();
            } catch (IOException ignored) {
                // Best-effort cleanup during application shutdown.
            }
            try {
                directory.close();
            } catch (IOException ignored) {
                // Best-effort cleanup during application shutdown.
            }
            analyzer.close();
        }
    }
}
