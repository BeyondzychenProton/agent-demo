package com.beyondzy.demo.mapper.display;

import com.beyondzy.demo.domain.display.DisplayMessageBean;
import com.beyondzy.demo.domain.display.DisplayTurnBean;
import com.beyondzy.demo.domain.display.DisplayViewBean;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/** 会话展示读索引 Mapper，集中维护 display_* 表的 SQL。 */
@Mapper
public interface DisplayMapper {
    /** 读取展示快照水位 seq。 */
    @Select("SELECT seq FROM display_head WHERE thread_id=#{threadId}")
    Long findSnapshotSeq(@Param("threadId") String threadId);

    /** 按 ordinal 顺序读取快照消息。 */
    @Select("SELECT message_id, role, content FROM display_message WHERE thread_id=#{threadId} ORDER BY ordinal")
    @Results({@Result(column="message_id", property="messageId"), @Result(column="role", property="role"),
            @Result(column="content", property="content")})
    List<DisplayMessageBean> findMessages(@Param("threadId") String threadId);

    /** 按 ordinal 顺序读取快照轮次。 */
    @Select("SELECT run_id, status FROM display_turn WHERE thread_id=#{threadId} ORDER BY ordinal")
    @Results({@Result(column="run_id", property="runId"), @Result(column="status", property="status")})
    List<DisplayTurnBean> findTurns(@Param("threadId") String threadId);

    /** 删除线程下全部消息，供快照整体重写。 */
    @Delete("DELETE FROM display_message WHERE thread_id=#{threadId}")
    int deleteMessages(@Param("threadId") String threadId);

    /** 删除线程下全部轮次，供快照整体重写。 */
    @Delete("DELETE FROM display_turn WHERE thread_id=#{threadId}")
    int deleteTurns(@Param("threadId") String threadId);

    /** 插入一条快照消息。 */
    @Insert("INSERT INTO display_message(thread_id,ordinal,message_id,role,content,gmt_create,gmt_modified) "
            + "VALUES(#{threadId},#{ordinal},#{messageId},#{role},#{content},#{now},#{now})")
    int insertMessage(@Param("threadId") String threadId, @Param("ordinal") int ordinal,
                      @Param("messageId") String messageId, @Param("role") String role,
                      @Param("content") String content, @Param("now") LocalDateTime now);

    /** 插入一条快照轮次。 */
    @Insert("INSERT INTO display_turn(thread_id,ordinal,run_id,status,gmt_create,gmt_modified) "
            + "VALUES(#{threadId},#{ordinal},#{runId},#{status},#{now},#{now})")
    int insertTurn(@Param("threadId") String threadId, @Param("ordinal") int ordinal,
                   @Param("runId") String runId, @Param("status") String status,
                   @Param("now") LocalDateTime now);

    @Insert("""
            <script>
            INSERT INTO display_message(thread_id,ordinal,message_id,role,content,gmt_create,gmt_modified) VALUES
            <foreach collection="rows" item="row" index="ordinal" separator=",">
            (#{threadId},#{ordinal},#{row.messageId},#{row.role},#{row.content},#{now},#{now})
            </foreach>
            </script>
            """)
    int insertMessages(@Param("threadId") String threadId, @Param("rows") List<DisplayMessageBean> rows,
                       @Param("now") LocalDateTime now);

    @Insert("""
            <script>
            INSERT INTO display_turn(thread_id,ordinal,run_id,status,gmt_create,gmt_modified) VALUES
            <foreach collection="rows" item="row" index="ordinal" separator=",">
            (#{threadId},#{ordinal},#{row.runId},#{row.status},#{now},#{now})
            </foreach>
            </script>
            """)
    int insertTurns(@Param("threadId") String threadId, @Param("rows") List<DisplayTurnBean> rows,
                    @Param("now") LocalDateTime now);

    /** 幂等写入/更新展示快照水位。 */
    @Insert("INSERT INTO display_head(thread_id,seq,gmt_create,gmt_modified) VALUES(#{threadId},#{seq},#{now},#{now}) "
            + "ON DUPLICATE KEY UPDATE seq=VALUES(seq), gmt_modified=VALUES(gmt_modified)")
    int saveSnapshotSeq(@Param("threadId") String threadId, @Param("seq") long seq,
                        @Param("now") LocalDateTime now);

    /** 读取指定 seq 的完整视图缓存。 */
    @Select("SELECT format, is_interactive, payload FROM display_view WHERE thread_id=#{threadId} AND seq=#{seq}")
    @Results({@Result(column="format", property="format"),
            @Result(column="is_interactive", property="hasInteractions"),
            @Result(column="payload", property="payload")})
    DisplayViewBean findView(@Param("threadId") String threadId, @Param("seq") long seq);

    /** 幂等保存视图缓存；仅当新 seq 不落后时覆盖 payload/元数据。 */
    @Insert("INSERT INTO display_view(thread_id,seq,format,is_interactive,payload,gmt_create,gmt_modified) "
            + "VALUES(#{threadId},#{seq},#{format},#{hasInteractions},#{payload},#{now},#{now}) "
            + "ON DUPLICATE KEY UPDATE payload=IF(VALUES(seq)>=seq,VALUES(payload),payload), "
            + "is_interactive=IF(VALUES(seq)>=seq,VALUES(is_interactive),is_interactive), "
            + "format=IF(VALUES(seq)>=seq,VALUES(format),format), seq=GREATEST(seq,VALUES(seq)), "
            + "gmt_modified=IF(VALUES(seq)>=seq,VALUES(gmt_modified),gmt_modified)")
    int saveView(@Param("threadId") String threadId, @Param("seq") long seq, @Param("format") int format,
                 @Param("hasInteractions") boolean hasInteractions, @Param("payload") String payload,
                 @Param("now") LocalDateTime now);
}
