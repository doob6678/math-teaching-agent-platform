package com.doob.mathagent.teacher.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.teacher.document.entity.TeacherSourceDocumentEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TeacherSourceDocumentMapper extends BaseMapper<TeacherSourceDocumentEntity> {

    List<TeacherSourceDocumentEntity> selectActiveFileByIdentity(
            @Param("tenantId") String tenantId,
            @Param("rootDocumentId") String rootDocumentId,
            @Param("providerItemId") String providerItemId,
            @Param("fileIdentityHash") String fileIdentityHash,
            @Param("pathIdentityHash") String pathIdentityHash,
            @Param("sourcePath") String sourcePath);

    List<TeacherSourceDocumentEntity> selectSearchableRootDocuments(
            @Param("tenantId") String tenantId,
            @Param("viewerRole") String viewerRole,
            @Param("viewerSubjectId") String viewerSubjectId);

    boolean existsArchivedFileByRoot(
            @Param("tenantId") String tenantId,
            @Param("rootDocumentId") String rootDocumentId);

    List<TeacherSourceDocumentEntity> selectSearchableFiles(
            @Param("tenantId") String tenantId,
            @Param("viewerRole") String viewerRole,
            @Param("viewerSubjectId") String viewerSubjectId,
            @Param("rootDocumentIds") List<Long> rootDocumentIds,
            @Param("limit") int limit);

    List<TeacherSourceDocumentEntity> selectSearchableFilesByIds(
            @Param("tenantId") String tenantId,
            @Param("viewerRole") String viewerRole,
            @Param("viewerSubjectId") String viewerSubjectId,
            @Param("fileDocumentIds") List<String> fileDocumentIds,
            @Param("limit") int limit);

    List<TeacherSourceDocumentEntity> selectFileDocumentsForIndexing(
            @Param("tenantId") String tenantId,
            @Param("rootDocumentId") String rootDocumentId,
            @Param("afterFileDocumentId") String afterFileDocumentId,
            @Param("limit") int limit);

    List<TeacherSourceDocumentEntity> selectMissingFileDocuments(
            @Param("tenantId") String tenantId,
            @Param("rootDocumentId") String rootDocumentId,
            @Param("activeFileIdentityHashes") List<String> activeFileIdentityHashes,
            @Param("afterFileDocumentId") String afterFileDocumentId,
            @Param("limit") int limit);

    int archiveFileDocument(
            @Param("tenantId") String tenantId,
            @Param("fileDocumentId") Long fileDocumentId);

    int archiveMissingFiles(
            @Param("tenantId") String tenantId,
            @Param("rootDocumentId") String rootDocumentId,
            @Param("activeFileIdentityHashes") List<String> activeFileIdentityHashes);
}
