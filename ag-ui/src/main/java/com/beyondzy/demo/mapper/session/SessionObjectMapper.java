package com.beyondzy.demo.mapper.session;

import com.beyondzy.demo.domain.session.SessionObjectBean;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 会话日志对象 Mapper，封装按用户隔离的读取和版本比较并交换操作。对应表：agent_session_object。 */
@Mapper
public interface SessionObjectMapper {

    /** 按复合主键读取对象；查无记录时返回 null。 */
    @Select("SELECT user_id, object_path, version, payload FROM agent_session_object "
            + "WHERE user_id = #{userId} AND object_path = #{objectPath}")
    @Results({
            @Result(column = "user_id", property = "userId"),
            @Result(column = "object_path", property = "objectPath"),
            @Result(column = "version", property = "version"),
            @Result(column = "payload", property = "payload")
    })
    SessionObjectBean findObject(@Param("userId") long userId, @Param("objectPath") String objectPath);

    /** 仅当对象尚不存在时创建首个版本。 */
    @Insert("INSERT INTO agent_session_object(user_id, object_path, version, payload, created_at, updated_at) "
            + "VALUES (#{userId}, #{objectPath}, 1, #{payload}, #{now}, #{now})")
    int insertFirstVersion(@Param("userId") long userId, @Param("objectPath") String objectPath,
                           @Param("payload") byte[] payload, @Param("now") long now);

    /** 版本匹配时递增版本并替换内容，借助受影响行数报告 CAS 是否成功。 */
    @Update("UPDATE agent_session_object SET version = version + 1, payload = #{payload}, updated_at = #{now} "
            + "WHERE user_id = #{userId} AND object_path = #{objectPath} AND version = #{expectedVersion}")
    int updateIfVersionMatches(@Param("userId") long userId, @Param("objectPath") String objectPath,
                               @Param("expectedVersion") long expectedVersion,
                               @Param("payload") byte[] payload, @Param("now") long now);
}
