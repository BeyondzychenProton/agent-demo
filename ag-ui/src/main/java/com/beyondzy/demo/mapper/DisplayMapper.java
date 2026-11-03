package com.beyondzy.demo.mapper;

import com.beyondzy.demo.domain.DisplayMessageBean;
import com.beyondzy.demo.domain.DisplayTurnBean;
import com.beyondzy.demo.domain.DisplayViewBean;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 会话展示读索引 Mapper，集中维护 display_* 表的 SQL。 */
@Mapper
public interface DisplayMapper {
    @Select("SELECT seq FROM display_head WHERE thread_id=#{threadId}")
    Long findSnapshotSeq(@Param("threadId") String threadId);

    @Select("SELECT message_id, role, content FROM display_message WHERE thread_id=#{threadId} ORDER BY ordinal")
    @Results({@Result(column="message_id", property="messageId"), @Result(column="role", property="role"),
            @Result(column="content", property="content")})
    List<DisplayMessageBean> findMessages(@Param("threadId") String threadId);

    @Select("SELECT run_id, status FROM display_turn WHERE thread_id=#{threadId} ORDER BY ordinal")
    @Results({@Result(column="run_id", property="runId"), @Result(column="status", property="status")})
    List<DisplayTurnBean> findTurns(@Param("threadId") String threadId);

    @Delete("DELETE FROM display_message WHERE thread_id=#{threadId}")
    int deleteMessages(@Param("threadId") String threadId);
    @Delete("DELETE FROM display_turn WHERE thread_id=#{threadId}")
    int deleteTurns(@Param("threadId") String threadId);

    @Insert("INSERT INTO display_message(thread_id,ordinal,message_id,role,content) VALUES(#{threadId},#{ordinal},#{messageId},#{role},#{content})")
    int insertMessage(@Param("threadId") String threadId, @Param("ordinal") int ordinal,
                      @Param("messageId") String messageId, @Param("role") String role, @Param("content") String content);
    @Insert("INSERT INTO display_turn(thread_id,ordinal,run_id,status) VALUES(#{threadId},#{ordinal},#{runId},#{status})")
    int insertTurn(@Param("threadId") String threadId, @Param("ordinal") int ordinal,
                   @Param("runId") String runId, @Param("status") String status);
    @Insert("INSERT INTO display_head(thread_id,seq) VALUES(#{threadId},#{seq}) ON DUPLICATE KEY UPDATE seq=VALUES(seq)")
    int saveSnapshotSeq(@Param("threadId") String threadId, @Param("seq") long seq);

    @Select("SELECT format, has_interactions, payload FROM display_view WHERE thread_id=#{threadId} AND seq=#{seq}")
    @Results({@Result(column="format", property="format"), @Result(column="has_interactions", property="hasInteractions"),
            @Result(column="payload", property="payload")})
    DisplayViewBean findView(@Param("threadId") String threadId, @Param("seq") long seq);

    @Insert("INSERT INTO display_view(thread_id,seq,format,has_interactions,payload) VALUES(#{threadId},#{seq},#{format},#{hasInteractions},#{payload}) "
            + "ON DUPLICATE KEY UPDATE payload=IF(VALUES(seq)>=seq,VALUES(payload),payload), "
            + "has_interactions=IF(VALUES(seq)>=seq,VALUES(has_interactions),has_interactions), "
            + "format=IF(VALUES(seq)>=seq,VALUES(format),format), seq=GREATEST(seq,VALUES(seq))")
    int saveView(@Param("threadId") String threadId, @Param("seq") long seq, @Param("format") int format,
                 @Param("hasInteractions") boolean hasInteractions, @Param("payload") String payload);
}
