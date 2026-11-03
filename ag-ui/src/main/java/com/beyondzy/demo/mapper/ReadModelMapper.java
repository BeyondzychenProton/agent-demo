package com.beyondzy.demo.mapper;

import com.beyondzy.demo.domain.ReadModelHeadBean;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/** 读模型数据访问 Mapper；读模型业务规则和事务由上层协调。 */
@Mapper
public interface ReadModelMapper {
    @Select("SELECT generation, revision, exec_seq, inbox_seq, updated_at, lease_tag, checkpoint, control "
            + "FROM rm_head WHERE thread=#{thread}")
    @Results({@Result(column="generation",property="generation"), @Result(column="revision",property="revision"),
            @Result(column="exec_seq",property="execSeq"), @Result(column="inbox_seq",property="inboxSeq"),
            @Result(column="updated_at",property="updatedAt"), @Result(column="lease_tag",property="leaseTag"),
            @Result(column="checkpoint",property="checkpoint"), @Result(column="control",property="control")})
    ReadModelHeadBean findHead(@Param("thread") String thread);

    @Select("SELECT payload FROM rm_raw WHERE thread=#{thread} AND id=#{id}")
    String findRaw(@Param("thread") String thread, @Param("id") String id);

    @Select("SELECT order_key FROM rm_item WHERE thread=#{thread} AND id=#{id} AND until_revision IS NULL")
    Long findCurrentOrder(@Param("thread") String thread, @Param("id") String id);

    @Select("SELECT COALESCE(MAX(order_key),0) FROM rm_item WHERE thread=#{thread}")
    long findMaxOrder(@Param("thread") String thread);

    @Select("SELECT i.payload FROM rm_pending p JOIN rm_item i ON p.thread=i.thread AND p.id=i.id "
            + "WHERE p.thread=#{thread} AND i.until_revision IS NULL AND p.order_key>#{after} "
            + "ORDER BY p.order_key LIMIT #{limit}")
    List<String> findPendingPayloads(@Param("thread") String thread, @Param("after") long after,
                                     @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM rm_pending WHERE thread=#{thread}")
    int countPending(@Param("thread") String thread);

    @Delete("DELETE FROM rm_content WHERE thread=#{thread}") int deleteContent(@Param("thread") String thread);
    @Delete("DELETE FROM rm_change WHERE thread=#{thread}") int deleteChanges(@Param("thread") String thread);
    @Delete("DELETE FROM rm_pending WHERE thread=#{thread}") int deletePending(@Param("thread") String thread);
    @Delete("DELETE FROM rm_item WHERE thread=#{thread}") int deleteItems(@Param("thread") String thread);
    @Delete("DELETE FROM rm_raw WHERE thread=#{thread}") int deleteRaw(@Param("thread") String thread);
    @Delete("DELETE FROM rm_head WHERE thread=#{thread}") int deleteHead(@Param("thread") String thread);
}
