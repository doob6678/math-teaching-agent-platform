package com.doob.mathagent.teacher.sync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.teacher.sync.MyBatisTeacherSourceSyncManifestStore.TeacherSourceSyncManifestEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for durable per-file Feishu sync state. */
@Mapper
public interface TeacherSourceSyncManifestMapper extends BaseMapper<TeacherSourceSyncManifestEntity> {

    /**
     * SQL 位于 mapper/TeacherSourceSyncManifestMapper.xml；全部取值 #{} 参数绑定，满足 SQL 注入防护守卫。
     */
    List<String> selectProviderItemIdByPath(
            @Param("tenantId") String tenantId,
            @Param("documentId") String documentId,
            @Param("sourcePath") String sourcePath,
            @Param("pathWithoutExportExtension") String pathWithoutExportExtension);
}
