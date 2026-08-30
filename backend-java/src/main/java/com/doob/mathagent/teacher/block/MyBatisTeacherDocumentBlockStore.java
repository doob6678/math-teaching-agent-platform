package com.doob.mathagent.teacher.block;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.doob.mathagent.teacher.block.entity.TeacherDocumentBlockEntity;
import com.doob.mathagent.teacher.block.mapper.TeacherDocumentBlockMapper;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed document block store.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisTeacherDocumentBlockStore implements TeacherDocumentBlockStore {

    private final TeacherDocumentBlockMapper mapper;
    private final TeacherResourceBm25SearchEngine bm25SearchEngine;

    /**
     * Creates a MyBatis-backed block store.
     *
     * @param mapper document block mapper
     */
    public MyBatisTeacherDocumentBlockStore(
            TeacherDocumentBlockMapper mapper,
            TeacherResourceBm25SearchEngine bm25SearchEngine) {
        this.mapper = mapper;
        this.bm25SearchEngine = bm25SearchEngine;
    }

    /**
     * Marks old blocks inactive and inserts the new active parse result.
     */
    @Override
    public List<TeacherDocumentBlockResponse> replaceActiveBlocks(
            String tenantId,
            String documentId,
            List<TeacherDocumentBlockResponse> blocks) {
        Long sourceDocumentId = parseId(documentId);
        if (sourceDocumentId == null) {
            return List.of();
        }
        List<TeacherDocumentBlockEntity> existingActive = mapper.selectList(new LambdaQueryWrapper<TeacherDocumentBlockEntity>()
                .eq(TeacherDocumentBlockEntity::getSourceDocumentId, sourceDocumentId)
                .eq(TeacherDocumentBlockEntity::getStatus, "active")
                .orderByAsc(TeacherDocumentBlockEntity::getBlockOrder)
                .orderByAsc(TeacherDocumentBlockEntity::getId));
        Map<String, TeacherDocumentBlockEntity> existingBySourceKey = new LinkedHashMap<>();
        for (TeacherDocumentBlockEntity entity : existingActive) {
            existingBySourceKey.put(sourceKey(entity.getExternalBlockId(), entity.getId()), entity);
        }
        Map<String, Boolean> seenIncomingKeys = new LinkedHashMap<>();
        for (TeacherDocumentBlockResponse block : blocks) {
            String sourceKey = sourceKey(block.externalBlockId(), parseId(block.blockId()));
            seenIncomingKeys.put(sourceKey, Boolean.TRUE);
            TeacherDocumentBlockEntity existing = existingBySourceKey.get(sourceKey);
            TeacherDocumentBlockEntity entity = toEntity(block);
            entity.setSourceDocumentId(sourceDocumentId);
            entity.setStatus("active");
            if (existing != null) {
                /*
                 * Do not mark everything inactive and reinsert from scratch here. Stable block ids are part of the
                 * incremental-sync contract: question-bank source links, vector cleanup, and document-internal rerank
                 * depend on a block keeping the same primary key while its checksum/text updates in place.
                 */
                entity.setId(existing.getId());
                mapper.updateById(entity);
                continue;
            }
            mapper.insert(entity);
        }
        for (TeacherDocumentBlockEntity entity : existingActive) {
            if (seenIncomingKeys.containsKey(sourceKey(entity.getExternalBlockId(), entity.getId()))) {
                continue;
            }
            mapper.update(
                    null,
                    new LambdaUpdateWrapper<TeacherDocumentBlockEntity>()
                            .eq(TeacherDocumentBlockEntity::getId, entity.getId())
                            .set(TeacherDocumentBlockEntity::getStatus, "inactive"));
        }
        bm25SearchEngine.invalidateTenant(tenantId);
        return listByDocument(tenantId, documentId);
    }

    @Override
    public void completeFileReplacement(String tenantId, String fileDocumentId) {
        bm25SearchEngine.invalidateTenant(tenantId);
    }

    @Override
    public void beginFileReplacement(String tenantId, String fileDocumentId) {
        Long sourceDocumentId = parseId(fileDocumentId);
        if (sourceDocumentId == null || tenantId == null || tenantId.isBlank()) {
            return;
        }
        bm25SearchEngine.invalidateTenant(tenantId);
        mapper.retireActiveForFile(tenantId, sourceDocumentId);
    }

    @Override
    public List<TeacherDocumentBlockResponse> replaceActiveBlockBatch(
            String tenantId,
            String fileDocumentId,
            List<TeacherDocumentBlockResponse> blocks) {
        Long sourceDocumentId = parseId(fileDocumentId);
        if (sourceDocumentId == null || tenantId == null || tenantId.isBlank()
                || blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<String> externalIds = blocks.stream()
                .map(TeacherDocumentBlockResponse::externalBlockId)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        Map<String, TeacherDocumentBlockEntity> existingByExternalId = new LinkedHashMap<>();
        if (!externalIds.isEmpty()) {
            for (TeacherDocumentBlockEntity entity : mapper.selectByExternalIds(
                    tenantId, sourceDocumentId, externalIds, Math.min(128, externalIds.size()))) {
                if (entity.getExternalBlockId() != null && !entity.getExternalBlockId().isBlank()) {
                    existingByExternalId.putIfAbsent(entity.getExternalBlockId(), entity);
                }
            }
        }
        for (TeacherDocumentBlockResponse block : blocks) {
            TeacherDocumentBlockEntity entity = toEntity(block);
            entity.setSourceDocumentId(sourceDocumentId);
            entity.setStatus("active");
            TeacherDocumentBlockEntity existing = existingByExternalId.get(block.externalBlockId());
            if (existing != null) {
                entity.setId(existing.getId());
                mapper.updateById(entity);
            } else {
                mapper.insert(entity);
            }
        }
        bm25SearchEngine.invalidateTenant(tenantId);
        return blocks;
    }
    /** Lists active blocks for a numeric source document. */
    @Override
    public List<TeacherDocumentBlockResponse> listByDocument(String tenantId, String documentId) {
        Long sourceDocumentId = parseId(documentId);
        if (sourceDocumentId == null) {
            return List.of();
        }
        LambdaQueryWrapper<TeacherDocumentBlockEntity> query = new LambdaQueryWrapper<TeacherDocumentBlockEntity>()
                .eq(TeacherDocumentBlockEntity::getSourceDocumentId, sourceDocumentId)
                .eq(TeacherDocumentBlockEntity::getStatus, "active")
                .orderByAsc(TeacherDocumentBlockEntity::getBlockOrder)
                .orderByAsc(TeacherDocumentBlockEntity::getId);
        return mapper.selectList(query).stream()
                .map(MyBatisTeacherDocumentBlockStore::toResponse)
                .toList();
    }

    /**
     * Irreversibly clears parsed source payloads for an archived document while preserving rows for audit joins.
     */
    @Override
    public void purgeDocumentContent(String tenantId, String documentId) {
        Long sourceDocumentId = parseId(documentId);
        if (sourceDocumentId == null) {
            return;
        }
        mapper.update(
                null,
                new LambdaUpdateWrapper<TeacherDocumentBlockEntity>()
                        .eq(TeacherDocumentBlockEntity::getSourceDocumentId, sourceDocumentId)
                        .set(TeacherDocumentBlockEntity::getRawText, null)
                        .set(TeacherDocumentBlockEntity::getNormalizedText, null)
                        .set(TeacherDocumentBlockEntity::getImageRefs, "[]")
                        .set(TeacherDocumentBlockEntity::getFormulaRefs, "[]")
                        .set(TeacherDocumentBlockEntity::getGraphNodeIdsJson, "[]")
                        .set(TeacherDocumentBlockEntity::getGraphTagNamesJson, "[]")
                        .set(TeacherDocumentBlockEntity::getStatus, "purged"));
        bm25SearchEngine.invalidateTenant(tenantId);
    }

    /**
     * Batch-loads active blocks for multiple documents in one SQL query so teacher search can avoid a per-document
     * round-trip before semantic rerank has even decided which documents are worth keeping.
     */
    @Override
    public Map<String, List<TeacherDocumentBlockResponse>> listByDocuments(String tenantId, List<String> documentIds) {
        Map<Long, String> documentIdByNumericId = new LinkedHashMap<>();
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        for (String documentId : documentIds) {
            Long numericId = parseId(documentId);
            if (numericId != null) {
                documentIdByNumericId.put(numericId, documentId);
            }
        }
        if (documentIdByNumericId.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<TeacherDocumentBlockEntity> query = new LambdaQueryWrapper<TeacherDocumentBlockEntity>()
                .in(TeacherDocumentBlockEntity::getSourceDocumentId, documentIdByNumericId.keySet())
                .eq(TeacherDocumentBlockEntity::getStatus, "active")
                .orderByAsc(TeacherDocumentBlockEntity::getSourceDocumentId)
                .orderByAsc(TeacherDocumentBlockEntity::getBlockOrder)
                .orderByAsc(TeacherDocumentBlockEntity::getId);
        Map<String, List<TeacherDocumentBlockResponse>> blocksByDocumentId = new LinkedHashMap<>();
        for (String documentId : documentIds) {
            if (documentId != null && !documentId.isBlank()) {
                blocksByDocumentId.put(documentId, new ArrayList<>());
            }
        }
        for (TeacherDocumentBlockEntity entity : mapper.selectList(query)) {
            String documentId = documentIdByNumericId.get(entity.getSourceDocumentId());
            if (documentId == null) {
                continue;
            }
            blocksByDocumentId.computeIfAbsent(documentId, ignored -> new ArrayList<>())
                    .add(toResponse(entity));
        }
        return blocksByDocumentId;
    }

    /** Returns selected active blocks for one FILE document by persisted block ids. */
    @Override
    public List<TeacherDocumentBlockResponse> listBlocksByIds(
            String tenantId, String fileDocumentId, List<String> blockIds, int limit) {
        Long sourceDocumentId = parseId(fileDocumentId);
        List<Long> numericBlockIds = numericIds(blockIds);
        if (sourceDocumentId == null || tenantId == null || tenantId.isBlank()
                || numericBlockIds.isEmpty() || limit <= 0) {
            return List.of();
        }
        return mapper.selectActiveByIds(
                        tenantId,
                        sourceDocumentId,
                        numericBlockIds,
                        Math.min(limit, 128))
                .stream()
                .map(MyBatisTeacherDocumentBlockStore::toResponse)
                .toList();
    }

    /** Returns a bounded active block-order window from one FILE document. */
    @Override
    public List<TeacherDocumentBlockResponse> listEvidenceWindow(
            String tenantId, String fileDocumentId, int centerBlockOrder, int radius, int limit) {
        Long sourceDocumentId = parseId(fileDocumentId);
        if (sourceDocumentId == null || tenantId == null || tenantId.isBlank()
                || centerBlockOrder < 0 || radius < 0 || limit <= 0) {
            return List.of();
        }
        int boundedRadius = Math.min(radius, 16);
        return mapper.selectActiveWindow(
                        tenantId,
                        sourceDocumentId,
                        Math.max(0, centerBlockOrder - boundedRadius),
                        centerBlockOrder + boundedRadius,
                        Math.min(limit, 64))
                .stream()
                .map(MyBatisTeacherDocumentBlockStore::toResponse)
                .toList();
    }

    /** Returns one bounded active block page for a FILE document. */
    @Override
    public List<TeacherDocumentBlockResponse> listBlocksForFile(
            String tenantId, String fileDocumentId, int limit, Integer afterBlockOrder) {
        Long sourceDocumentId = parseId(fileDocumentId);
        if (sourceDocumentId == null || tenantId == null || tenantId.isBlank() || limit <= 0) {
            return List.of();
        }
        return mapper.selectActivePage(
                        tenantId,
                        sourceDocumentId,
                        Math.min(limit, 512),
                        afterBlockOrder)
                .stream()
                .map(MyBatisTeacherDocumentBlockStore::toResponse)
                .toList();
    }

    /** Returns BM25-ranked blocks from the embedded Lucene index with live SQL authorization revalidation. */
    @Override
    public List<TeacherDocumentBlockResponse> searchFileBlocksByLexicalTerms(
            String tenantId, String viewerRole, String viewerSubjectId, List<String> terms, int limit) {
        return searchFileBlocksByLexicalTerms(
                tenantId, viewerRole, viewerSubjectId, terms, limit,
                com.doob.mathagent.teacher.search.TeacherResourceSearchFilter.EMPTY);
    }

    /** Returns BM25-ranked blocks with explicit FILE and permission filters applied in both snapshot and revalidation. */
    @Override
    public List<TeacherDocumentBlockResponse> searchFileBlocksByLexicalTerms(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            List<String> terms,
            int limit,
            com.doob.mathagent.teacher.search.TeacherResourceSearchFilter filter) {
        return bm25SearchEngine.search(tenantId, viewerRole, viewerSubjectId, terms, Math.min(limit, 96), filter)
                .stream()
                .map(MyBatisTeacherDocumentBlockStore::toResponse)
                .toList();
    }

    /** Returns one active block per visible FILE from the SQL-bounded graph-tag route. */
    @Override
    public List<TeacherDocumentBlockResponse> searchFileBlocksByGraphTags(
            String tenantId, String viewerRole, String viewerSubjectId, List<String> tagNames, int limit) {
        List<String> normalizedTags = normalizedTerms(tagNames);
        if (tenantId == null || tenantId.isBlank() || normalizedTags.isEmpty() || limit <= 0) {
            return List.of();
        }
        return mapper.selectSearchableFileBlocksByGraphTags(
                        tenantId, viewerRole, viewerSubjectId, normalizedTags, Math.min(limit, 96))
                .stream()
                .map(MyBatisTeacherDocumentBlockStore::toResponse)
                .toList();
    }

    /** Converts a response to a MyBatis entity. */
    private static TeacherDocumentBlockEntity toEntity(TeacherDocumentBlockResponse block) {
        TeacherDocumentBlockEntity entity = new TeacherDocumentBlockEntity();
        entity.setId(parseId(block.blockId()));
        entity.setSourceDocumentId(parseId(block.documentId()));
        entity.setExternalBlockId(block.externalBlockId());
        entity.setBlockType(block.blockType());
        entity.setBlockOrder(block.blockOrder());
        entity.setChapter(block.chapter());
        entity.setSection(block.section());
        entity.setPageNo(block.pageNo());
        entity.setPrintedPageNo(block.printedPageNo());
        entity.setSourcePath(block.sourcePath());
        entity.setBlockRole(block.blockRole());
        entity.setRawText(block.rawText());
        entity.setNormalizedText(block.normalizedText());
        entity.setImageRefs(block.imageRefs());
        entity.setFormulaRefs(block.formulaRefs());
        entity.setGraphNodeIdsJson(block.graphNodeIdsJson());
        entity.setGraphTagNamesJson(block.graphTagNamesJson());
        entity.setChecksum(block.checksum());
        entity.setConfidence(block.confidence());
        entity.setStatus(block.status());
        return entity;
    }

    /**
     * Converts a MyBatis entity to a response.
     */
    private static TeacherDocumentBlockResponse toResponse(TeacherDocumentBlockEntity entity) {
        return new TeacherDocumentBlockResponse(
                entity.getId() == null ? "" : String.valueOf(entity.getId()),
                entity.getSourceDocumentId() == null ? "" : String.valueOf(entity.getSourceDocumentId()),
                entity.getExternalBlockId(),
                entity.getBlockType(),
                entity.getBlockOrder() == null ? 0 : entity.getBlockOrder(),
                entity.getChapter(),
                entity.getSection(),
                entity.getPageNo(),
                entity.getPrintedPageNo(),
                entity.getSourcePath(),
                blankToDefault(entity.getBlockRole(), "reference"),
                entity.getRawText(),
                entity.getNormalizedText(),
                entity.getImageRefs(),
                entity.getFormulaRefs(),
                blankToDefault(entity.getGraphNodeIdsJson(), "[]"),
                blankToDefault(entity.getGraphTagNamesJson(), "[]"),
                entity.getChecksum(),
                entity.getConfidence() == null ? 0.0 : entity.getConfidence(),
                entity.getStatus());
    }

    private static List<String> normalizedTerms(List<String> values) {
        return (values == null ? List.<String>of() : values).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(32)
                .toList();
    }

    private static List<Long> numericIds(List<String> values) {
        List<Long> result = new ArrayList<>();
        for (String value : values == null ? List.<String>of() : values) {
            Long id = parseId(value);
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }
    private static String sourceKey(String externalBlockId, Long fallbackId) {
        if (externalBlockId != null && !externalBlockId.isBlank()) {
            return externalBlockId.strip();
        }
        return fallbackId == null ? "" : String.valueOf(fallbackId);
    }

    /**
     * Parses a numeric database id.
     */
    private static Long parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}


