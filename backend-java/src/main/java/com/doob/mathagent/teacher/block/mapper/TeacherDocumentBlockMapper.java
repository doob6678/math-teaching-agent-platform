package com.doob.mathagent.teacher.block.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.teacher.block.entity.TeacherDocumentBlockEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TeacherDocumentBlockMapper extends BaseMapper<TeacherDocumentBlockEntity> {

    List<TeacherDocumentBlockEntity> selectActiveByIds(
            @Param("tenantId") String tenantId,
            @Param("fileDocumentId") Long fileDocumentId,
            @Param("blockIds") List<Long> blockIds,
            @Param("limit") int limit);

    List<TeacherDocumentBlockEntity> selectActiveWindow(
            @Param("tenantId") String tenantId,
            @Param("fileDocumentId") Long fileDocumentId,
            @Param("startOrder") int startOrder,
            @Param("endOrder") int endOrder,
            @Param("limit") int limit);

    List<TeacherDocumentBlockEntity> selectByExternalIds(
            @Param("tenantId") String tenantId,
            @Param("fileDocumentId") Long fileDocumentId,
            @Param("externalBlockIds") List<String> externalBlockIds,
            @Param("limit") int limit);

    int retireActiveForFile(
            @Param("tenantId") String tenantId,
            @Param("fileDocumentId") Long fileDocumentId);

    List<TeacherDocumentBlockEntity> selectActivePage(
            @Param("tenantId") String tenantId,
            @Param("fileDocumentId") Long fileDocumentId,
            @Param("limit") int limit,
            @Param("afterBlockOrder") Integer afterBlockOrder);

    /**
     * Returns one ranked active block per visible Feishu FILE for SQL-bounded lexical recall.
     * ROOT rows participate only in the authorization/synchronization EXISTS check.
     */
    List<TeacherDocumentBlockEntity> selectSearchableFileBlocksByLexicalTerms(
            @Param("tenantId") String tenantId,
            @Param("viewerRole") String viewerRole,
            @Param("viewerSubjectId") String viewerSubjectId,
            @Param("terms") List<String> terms,
            @Param("limit") int limit);

    /** Returns the bounded authorized FILE/block snapshot used to build the embedded BM25 index. */
    List<TeacherDocumentBlockEntity> selectSearchableFileBlocksForBm25Index(
            @Param("tenantId") String tenantId,
            @Param("viewerRole") String viewerRole,
            @Param("viewerSubjectId") String viewerSubjectId,
            @Param("documentIds") List<String> documentIds,
            @Param("permissionScopes") List<String> permissionScopes,
            @Param("afterBlockId") Long afterBlockId,
            @Param("limit") int limit);

    /** Revalidates BM25 hits against the current tenant, viewer, FILE, ROOT, filter and readiness boundary. */
    List<TeacherDocumentBlockEntity> selectSearchableFileBlocksByIds(
            @Param("tenantId") String tenantId,
            @Param("viewerRole") String viewerRole,
            @Param("viewerSubjectId") String viewerSubjectId,
            @Param("documentIds") List<String> documentIds,
            @Param("permissionScopes") List<String> permissionScopes,
            @Param("blockIds") List<Long> blockIds,
            @Param("limit") int limit);

    /** Returns one ranked active block per visible Feishu FILE whose persisted graph tags match the query graph. */
    List<TeacherDocumentBlockEntity> selectSearchableFileBlocksByGraphTags(
            @Param("tenantId") String tenantId,
            @Param("viewerRole") String viewerRole,
            @Param("viewerSubjectId") String viewerSubjectId,
            @Param("tagNames") List<String> tagNames,
            @Param("limit") int limit);
}
