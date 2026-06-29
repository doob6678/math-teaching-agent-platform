package com.doob.mathagent.teacher.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.doob.mathagent.teacher.entity.TeacherDocumentBlockEntity;
import com.doob.mathagent.teacher.mapper.TeacherDocumentBlockMapper;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import java.util.List;
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
        LambdaUpdateWrapper<TeacherDocumentBlockEntity> inactive = new LambdaUpdateWrapper<TeacherDocumentBlockEntity>()
                .eq(TeacherDocumentBlockEntity::getSourceDocumentId, sourceDocumentId)
                .eq(TeacherDocumentBlockEntity::getStatus, "active")
                .set(TeacherDocumentBlockEntity::getStatus, "inactive");
        mapper.update(null, inactive);
        for (TeacherDocumentBlockResponse block : blocks) {
            TeacherDocumentBlockEntity entity = toEntity(block);
            mapper.insert(entity);
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
        entity.setRawText(block.rawText());
        entity.setNormalizedText(block.normalizedText());
        entity.setImageRefs(block.imageRefs());
        entity.setFormulaRefs(block.formulaRefs());
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
                entity.getRawText(),
                entity.getNormalizedText(),
                entity.getImageRefs(),
                entity.getFormulaRefs(),
                entity.getChecksum(),
                entity.getConfidence() == null ? 0.0 : entity.getConfidence(),
                entity.getStatus());
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
}
