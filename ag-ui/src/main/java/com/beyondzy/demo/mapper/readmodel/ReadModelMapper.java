package com.beyondzy.demo.mapper.readmodel;

import com.beyondzy.demo.readmodel.ReadModelChangeRowBean;
import com.beyondzy.demo.readmodel.ReadModelContentBlockBean;
import com.beyondzy.demo.domain.readmodel.ReadModelHeadBean;
import com.beyondzy.demo.domain.readmodel.ReadModelItemPayloadBean;
import com.beyondzy.demo.domain.readmodel.ReadModelStaleItemBean;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 读模型 Mapper，集中全部 rm_* SQL。对应表族：rm_head / rm_raw / rm_item / rm_change / rm_pending / rm_block / rm_content。 */
@Mapper
public interface ReadModelMapper {

    /** 读取会话水位与检查点。 */
    @Select("SELECT generation, revision, exec_seq, inbox_seq, gmt_modified, lease_tag, checkpoint, control "
            + "FROM rm_head WHERE thread=#{thread}")
    @Results({@Result(column="generation",property="generation"), @Result(column="revision",property="revision"),
            @Result(column="exec_seq",property="execSeq"), @Result(column="inbox_seq",property="inboxSeq"),
            @Result(column="gmt_modified",property="gmtModified"), @Result(column="lease_tag",property="leaseTag"),
            @Result(column="checkpoint",property="checkpoint"), @Result(column="control",property="control")})
    ReadModelHeadBean findHead(@Param("thread") String thread);

    /** 读取原始条目载荷。 */
    @Select("SELECT payload FROM rm_raw WHERE thread=#{thread} AND id=#{id}")
    String findRaw(@Param("thread") String thread, @Param("id") String id);

    /** 读取当前版本条目的 order_key。 */
    @Select("SELECT order_key FROM rm_item WHERE thread=#{thread} AND id=#{id} AND until_revision IS NULL")
    Long findCurrentOrder(@Param("thread") String thread, @Param("id") String id);

    /** 读取会话内最大 order_key。 */
    @Select("SELECT COALESCE(MAX(order_key),0) FROM rm_item WHERE thread=#{thread}")
    long findMaxOrder(@Param("thread") String thread);

    /** 读取当前版本条目 payload（指纹比对用）。 */
    @Select("SELECT payload FROM rm_item WHERE thread=#{thread} AND id=#{id} AND until_revision IS NULL")
    @Results({@Result(column = "payload", property = "payload")})
    ReadModelItemPayloadBean findCurrentPayload(@Param("thread") String thread, @Param("id") String id);

    /** 按 pending 游标读取待交互条目的当前 payload。 */
    @Select("SELECT i.payload FROM rm_pending p JOIN rm_item i ON p.thread=i.thread AND p.id=i.id "
            + "WHERE p.thread=#{thread} AND i.until_revision IS NULL AND p.order_key>#{after} "
            + "ORDER BY p.order_key LIMIT #{limit}")
    List<String> findPendingPayloads(@Param("thread") String thread, @Param("after") long after,
                                     @Param("limit") int limit);

    /** 统计会话 pending 条数。 */
    @Select("SELECT COUNT(*) FROM rm_pending WHERE thread=#{thread}")
    int countPending(@Param("thread") String thread);

    /** 当前水位下按 order_key 倒序分页读取当前版本 payload。 */
    @Select("SELECT payload FROM rm_item WHERE thread=#{thread} AND revision<=#{revision} AND until_revision IS NULL "
            + "AND order_key<#{before} ORDER BY order_key DESC LIMIT #{limit}")
    List<String> findPageCurrent(@Param("thread") String thread, @Param("revision") long revision,
                                 @Param("before") long before, @Param("limit") int limit);

    /** 历史水位下按 order_key 倒序分页读取当时可见版本 payload。 */
    @Select("SELECT payload FROM rm_item WHERE thread=#{thread} AND revision<=#{revision} "
            + "AND (until_revision IS NULL OR until_revision>#{revision}) AND order_key<#{before} "
            + "ORDER BY order_key DESC LIMIT #{limit}")
    List<String> findPageAtRevision(@Param("thread") String thread, @Param("revision") long revision,
                                    @Param("before") long before, @Param("limit") int limit);

    /** 读取变更表最小 revision，用于判断补读窗口是否过期。 */
    @Select("SELECT MIN(revision) FROM rm_change WHERE thread=#{thread}")
    Long findMinChangeRevision(@Param("thread") String thread);

    /** 读取指定 revision 之后的变更行（含对应 item payload）。 */
    @Select("SELECT c.revision, i.payload FROM rm_change c JOIN rm_item i "
            + "ON c.thread=i.thread AND c.item_id=i.id AND c.revision=i.revision "
            + "WHERE c.thread=#{thread} AND c.revision>#{after} ORDER BY c.revision LIMIT #{limit}")
    @Results({@Result(column = "revision", property = "revision"),
            @Result(column = "payload", property = "payload")})
    List<ReadModelChangeRowBean> findChangesAfter(@Param("thread") String thread, @Param("after") long after,
                                                  @Param("limit") int limit);

    /** 按字段与块序号读取内容分块。 */
    @Select("SELECT b.body, c.encoding FROM rm_content c JOIN rm_block b ON c.hash=b.hash "
            + "WHERE c.thread=#{thread} AND c.id=#{id} AND c.revision=#{revision} AND c.field=#{field} "
            + "AND c.block_no>=#{after} ORDER BY c.block_no LIMIT #{limit}")
    @Results({@Result(column = "body", property = "body"),
            @Result(column = "encoding", property = "encoding")})
    List<ReadModelContentBlockBean> findContentBlocks(@Param("thread") String thread, @Param("id") String id,
                                                      @Param("revision") long revision, @Param("field") String field,
                                                      @Param("after") int after, @Param("limit") int limit);

    /** 查找可清理的过期旧版本条目。 */
    @Select("SELECT old.thread, old.id, old.revision FROM rm_item old "
            + "WHERE old.until_revision IS NOT NULL AND old.gmt_create<#{changeTtlCutoff} "
            + "AND NOT EXISTS(SELECT 1 FROM rm_change c WHERE c.thread=old.thread AND c.revision=old.revision) "
            + "AND NOT EXISTS(SELECT 1 FROM rm_item newer WHERE newer.thread=old.thread AND newer.id=old.id "
            + "AND newer.revision>=old.until_revision AND newer.gmt_create>=#{snapshotTtlCutoff}) LIMIT 1000")
    @Results({@Result(column = "thread", property = "thread"), @Result(column = "id", property = "id"),
            @Result(column = "revision", property = "revision")})
    List<ReadModelStaleItemBean> findStaleItems(@Param("changeTtlCutoff") LocalDateTime changeTtlCutoff,
                                                @Param("snapshotTtlCutoff") LocalDateTime snapshotTtlCutoff);

    /** 幂等写入/更新原始条目。 */
    @Insert("INSERT INTO rm_raw(thread, id, payload, gmt_create, gmt_modified) "
            + "VALUES(#{thread}, #{id}, #{payload}, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE payload=VALUES(payload), gmt_modified=VALUES(gmt_modified)")
    int upsertRaw(@Param("thread") String thread, @Param("id") String id, @Param("payload") String payload,
                  @Param("now") LocalDateTime now);

    /** 关闭当前打开的条目版本（设置 until_revision）。 */
    @Update("UPDATE rm_item SET until_revision=#{untilRevision}, gmt_modified=#{now} "
            + "WHERE thread=#{thread} AND id=#{id} AND until_revision IS NULL")
    int closeItemVersion(@Param("thread") String thread, @Param("id") String id,
                         @Param("untilRevision") long untilRevision, @Param("now") LocalDateTime now);

    /** 插入新的条目版本（until_revision 为空表示当前打开）。 */
    @Insert("INSERT INTO rm_item(thread, id, revision, until_revision, order_key, payload, gmt_create, gmt_modified) "
            + "VALUES(#{thread}, #{id}, #{revision}, NULL, #{orderKey}, #{payload}, #{now}, #{now})")
    int insertItem(@Param("thread") String thread, @Param("id") String id, @Param("revision") long revision,
                   @Param("orderKey") long orderKey, @Param("payload") String payload,
                   @Param("now") LocalDateTime now);

    /** 插入一条变更记录。 */
    @Insert("INSERT INTO rm_change(thread, revision, item_id, gmt_create, gmt_modified) "
            + "VALUES(#{thread}, #{revision}, #{itemId}, #{now}, #{now})")
    int insertChange(@Param("thread") String thread, @Param("revision") long revision,
                     @Param("itemId") String itemId, @Param("now") LocalDateTime now);

    /** 删除指定条目的 pending 索引。 */
    @Delete("DELETE FROM rm_pending WHERE thread=#{thread} AND id=#{id}")
    int deletePendingItem(@Param("thread") String thread, @Param("id") String id);

    /** 插入 pending 索引行。 */
    @Insert("INSERT INTO rm_pending(thread, id, order_key, gmt_create, gmt_modified) "
            + "VALUES(#{thread}, #{id}, #{orderKey}, #{now}, #{now})")
    int insertPending(@Param("thread") String thread, @Param("id") String id, @Param("orderKey") long orderKey,
                      @Param("now") LocalDateTime now);

    /** 幂等写入/更新会话水位。 */
    @Insert("INSERT INTO rm_head(thread, generation, revision, exec_seq, inbox_seq, lease_tag, checkpoint, control, "
            + "gmt_create, gmt_modified) "
            + "VALUES(#{thread}, #{generation}, #{revision}, #{execSeq}, #{inboxSeq}, #{leaseTag}, #{checkpoint}, "
            + "#{control}, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE revision=VALUES(revision), exec_seq=VALUES(exec_seq), inbox_seq=VALUES(inbox_seq), "
            + "lease_tag=VALUES(lease_tag), checkpoint=VALUES(checkpoint), control=VALUES(control), "
            + "gmt_modified=VALUES(gmt_modified)")
    int upsertHead(@Param("thread") String thread, @Param("generation") String generation,
                   @Param("revision") long revision, @Param("execSeq") long execSeq,
                   @Param("inboxSeq") long inboxSeq, @Param("now") LocalDateTime now,
                   @Param("leaseTag") String leaseTag, @Param("checkpoint") String checkpoint,
                   @Param("control") String control);

    /** 幂等插入内容块正文（按 hash 去重）。 */
    @Insert("INSERT IGNORE INTO rm_block(hash, body, gmt_create, gmt_modified) "
            + "VALUES(#{hash}, #{body}, #{now}, #{now})")
    int insertBlockIgnore(@Param("hash") String hash, @Param("body") String body,
                          @Param("now") LocalDateTime now);

    /** 插入条目字段到内容块的映射。 */
    @Insert("INSERT INTO rm_content(thread, id, revision, field, block_no, hash, encoding, gmt_create, gmt_modified) "
            + "VALUES(#{thread}, #{id}, #{revision}, #{field}, #{blockNo}, #{hash}, #{encoding}, #{now}, #{now})")
    int insertContent(@Param("thread") String thread, @Param("id") String id, @Param("revision") long revision,
                      @Param("field") String field, @Param("blockNo") int blockNo,
                      @Param("hash") String hash, @Param("encoding") String encoding,
                      @Param("now") LocalDateTime now);

    /** 按 thread 清空内容映射（invalidate）。 */
    @Delete("DELETE FROM rm_content WHERE thread=#{thread}")
    int deleteContent(@Param("thread") String thread);

    /** 按 thread 清空变更（invalidate）。 */
    @Delete("DELETE FROM rm_change WHERE thread=#{thread}")
    int deleteChanges(@Param("thread") String thread);

    /** 按 thread 清空 pending（invalidate）。 */
    @Delete("DELETE FROM rm_pending WHERE thread=#{thread}")
    int deletePending(@Param("thread") String thread);

    /** 按 thread 清空条目（invalidate）。 */
    @Delete("DELETE FROM rm_item WHERE thread=#{thread}")
    int deleteItems(@Param("thread") String thread);

    /** 按 thread 清空原始条目（invalidate）。 */
    @Delete("DELETE FROM rm_raw WHERE thread=#{thread}")
    int deleteRaw(@Param("thread") String thread);

    /** 按 thread 清空水位（invalidate）。 */
    @Delete("DELETE FROM rm_head WHERE thread=#{thread}")
    int deleteHead(@Param("thread") String thread);

    /** 删除早于截止时间的变更记录（cleanup）。 */
    @Delete("DELETE FROM rm_change WHERE gmt_create<#{cutoff}")
    int deleteChangesBefore(@Param("cutoff") LocalDateTime cutoff);

    /** 删除指定条目的某个历史版本（cleanup）。 */
    @Delete("DELETE FROM rm_item WHERE thread=#{thread} AND id=#{id} AND revision=#{revision}")
    int deleteItemVersion(@Param("thread") String thread, @Param("id") String id, @Param("revision") long revision);

    @Delete("""
            <script>
            DELETE FROM rm_item WHERE (thread, id, revision) IN
            <foreach collection="items" item="item" open="(" separator="," close=")">
            (#{item.thread}, #{item.id}, #{item.revision})
            </foreach>
            </script>
            """)
    int deleteItemVersions(@Param("items") List<ReadModelStaleItemBean> items);

    /** 删除已无对应 item 版本的孤儿内容映射（cleanup）。 */
    @Delete("DELETE FROM rm_content WHERE NOT EXISTS("
            + "SELECT 1 FROM rm_item i WHERE i.thread=rm_content.thread AND i.id=rm_content.id AND i.revision=rm_content.revision)")
    int deleteOrphanContent();

    /** 删除已无内容引用的孤儿块正文（cleanup）。 */
    @Delete("DELETE FROM rm_block WHERE NOT EXISTS(SELECT 1 FROM rm_content c WHERE c.hash=rm_block.hash)")
    int deleteOrphanBlocks();
}
