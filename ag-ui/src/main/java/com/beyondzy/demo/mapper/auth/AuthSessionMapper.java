package com.beyondzy.demo.mapper.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.auth.AuthSessionBean;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 登录会话 Mapper。对应表：auth_session。 */
@Mapper
public interface AuthSessionMapper extends BaseMapper<AuthSessionBean> {
    @Update("UPDATE auth_session SET renewed_at = #{renewedAt}, gmt_modified = #{gmtModified} "
            + "WHERE id = #{id}")
    int updateRenewedAt(@Param("id") long id, @Param("renewedAt") LocalDateTime renewedAt,
                        @Param("gmtModified") LocalDateTime gmtModified);
}
