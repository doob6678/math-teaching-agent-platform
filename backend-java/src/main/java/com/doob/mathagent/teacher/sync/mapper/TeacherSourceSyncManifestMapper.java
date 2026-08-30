package com.doob.mathagent.teacher.sync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.teacher.sync.MyBatisTeacherSourceSyncManifestStore.TeacherSourceSyncManifestEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** MyBatis mapper for durable per-file Feishu sync state. */
@Mapper
public interface TeacherSourceSyncManifestMapper extends BaseMapper<TeacherSourceSyncManifestEntity> {

    @Select("SELECT provider_item_id FROM teacher_source_sync_manifest "
            + "WHERE tenant_id = #{tenantId} AND document_id = #{documentId} AND item_type IN ('file','docx') "
            + "AND (logical_path = #{sourcePath} OR local_path = #{sourcePath} "
            + "OR logical_path = #{pathWithoutExportExtension} "
            + "OR logical_path LIKE CONCAT('%/', #{sourcePath}) "
            + "OR logical_path LIKE CONCAT('%/', #{pathWithoutExportExtension}) "
            + "OR local_path LIKE CONCAT('%/', #{sourcePath}) "
            + "OR local_path LIKE CONCAT('%/', #{pathWithoutExportExtension})) "
            + "ORDER BY provider_item_id ASC LIMIT 1")
    List<String> selectProviderItemIdByPath(
            @Param("tenantId") String tenantId,
            @Param("documentId") String documentId,
            @Param("sourcePath") String sourcePath,
            @Param("pathWithoutExportExtension") String pathWithoutExportExtension);
}
