package com.beyondzy.demo.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.user.UserBean;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 用户表 MyBatis-Plus Mapper。对应表：app_user。 */
@Mapper
public interface UserMapper extends BaseMapper<UserBean> {
    /** 技能可见范围只需要账号是否还在，以及通讯录主键。 */
    @Select("""
            SELECT id, is_deleted, mantianxing_user_id
            FROM app_user
            WHERE id = #{id}
            """)
    UserBean findSkillViewer(@Param("id") long id);
}
