package com.doob.mathagent.teacher.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.doob.mathagent.teacher.entity.TeacherDocumentBlockEntity;
import com.doob.mathagent.teacher.mapper.TeacherDocumentBlockMapper;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
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

    /**
     * Creates a MyBatis-backed block store.
     *
     * @param mapper document block mapper
     */
    public MyBatisTeacherDocumentBlockStore(TeacherDocumentBlockMapper mapper) {
        this.mapper = mapper;
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
        return listByDocument(tenantId, documentId);
    }

    /**
     * Lists active blocks for a numeric source document.
     */
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
     * Converts a response to a MyBatis entity.
     */
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
