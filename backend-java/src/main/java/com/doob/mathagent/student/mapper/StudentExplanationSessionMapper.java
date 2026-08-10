package com.doob.mathagent.student.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.student.entity.StudentExplanationSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for explanation sessions.
 */
@Mapper
public interface StudentExplanationSessionMapper extends BaseMapper<StudentExplanationSessionEntity> {

    int appendMessage(
            @Param("conversationId") String conversationId,
            @Param("explanationId") String explanationId,
            @Param("questionText") String questionText,
            @Param("title") String title);

    int updateContextSummary(
            @Param("conversationId") String conversationId,
            @Param("expectedVersion") int expectedVersion,
            @Param("nextVersion") int nextVersion,
            @Param("fromMessageId") String fromMessageId,
            @Param("toMessageId") String toMessageId,
            @Param("contentHash") String contentHash,
            @Param("content") String content);
}
