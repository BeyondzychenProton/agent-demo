package com.beyondzy.demo.mapper.session;

import com.beyondzy.demo.domain.session.AgentSessionBean;
import java.time.LocalDateTime;
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
    @Insert("INSERT IGNORE INTO agent_session(user_id, agent_id, session_id, gmt_create, gmt_modified) "
            + "VALUES (#{userId}, #{agentId}, #{sessionId}, #{now}, #{now})")
    int insertIfAbsent(@Param("userId") long userId, @Param("agentId") String agentId,
                       @Param("sessionId") String sessionId, @Param("now") LocalDateTime now);

    /** 按创建时间倒序列出指定用户的会话。 */
    @Select("SELECT id, user_id, agent_id, session_id, gmt_create, gmt_modified FROM agent_session "
            + "WHERE user_id = #{userId} ORDER BY gmt_create DESC")
    @Results({
            @Result(column = "id", property = "id"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "agent_id", property = "agentId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "gmt_create", property = "gmtCreate"),
            @Result(column = "gmt_modified", property = "gmtModified")
    })
    List<AgentSessionBean> findByUserId(@Param("userId") long userId);

    /** 查询会话归属用户；不存在时返回 null。 */
    @Select("SELECT user_id FROM agent_session WHERE agent_id = #{agentId} AND session_id = #{sessionId} LIMIT 1")
    Long findUserId(@Param("agentId") String agentId, @Param("sessionId") String sessionId);
}
