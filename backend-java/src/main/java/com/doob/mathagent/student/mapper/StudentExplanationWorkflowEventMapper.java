package com.doob.mathagent.student.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.student.entity.StudentExplanationWorkflowEventEntity;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis mapper for append-only public student explanation workflow events. */
@Mapper
public interface StudentExplanationWorkflowEventMapper extends BaseMapper<StudentExplanationWorkflowEventEntity> {

    /**
     * Inserts one event row with an application-assigned {@code event_id} instead of MySQL AUTO_INCREMENT.
     *
     * <p>The Redis write-behind store allocates public cursor ids from a single Redis-backed sequence so the id it
     * hands to the SSE client matches the persisted row without a MySQL round-trip. MyBatis-Plus AUTO id handling
     * cannot be trusted to include a non-null key, so the explicit column list lives in the mapper XML.</p>
     */
    int insertWithExplicitId(StudentExplanationWorkflowEventEntity entity);

    /**
     * Highest event id currently durable in MySQL. The write-behind decorator seeds its Redis sequence from this
     * value on boot so application-assigned ids can never collide with rows a previous process already persisted.
     */
    long selectMaxEventId();
}
