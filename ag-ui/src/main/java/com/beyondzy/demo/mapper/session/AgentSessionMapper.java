package com.beyondzy.demo.mapper.session;

import com.beyondzy.demo.domain.session.AgentSessionBean;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Select;

/** 用户会话目录 Mapper。对应表：agent_session。 */
@Mapper
public interface AgentSessionMapper {
    /** 幂等登记会话，避免重复打开同一日志时产生冲突。 */
    @Insert("INSERT IGNORE INTO agent_session(user_id, agent_id, session_id, created_at, updated_at) "
            + "VALUES (#{userId}, #{agentId}, #{sessionId}, #{now}, #{now})")
    int insertIfAbsent(@Param("userId") long userId, @Param("agentId") String agentId,
                       @Param("sessionId") String sessionId, @Param("now") long now);

    /** 按创建时间倒序列出指定用户的会话。 */
    @Select("SELECT user_id, agent_id, session_id, created_at, updated_at FROM agent_session "
            + "WHERE user_id = #{userId} ORDER BY created_at DESC")
    @Results({
            @Result(column = "user_id", property = "userId"),
            @Result(column = "agent_id", property = "agentId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<AgentSessionBean> findByUserId(@Param("userId") long userId);
}
