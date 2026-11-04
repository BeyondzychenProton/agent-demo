package com.beyondzy.demo.mapper.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.auth.AuthSessionBean;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 登录会话 Mapper。对应表：auth_session。 */
@Mapper
public interface AuthSessionMapper extends BaseMapper<AuthSessionBean> {
    @Update("UPDATE auth_session SET renewed_at = #{renewedAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{id}")
    int updateRenewedAt(@Param("id") long id, @Param("renewedAt") long renewedAt,
                        @Param("updatedAt") long updatedAt);
}
