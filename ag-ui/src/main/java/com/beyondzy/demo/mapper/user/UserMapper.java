package com.beyondzy.demo.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beyondzy.demo.domain.user.UserBean;
import org.apache.ibatis.annotations.Mapper;

/** 用户表 MyBatis-Plus Mapper。对应表：app_user。 */
@Mapper
public interface UserMapper extends BaseMapper<UserBean> {}
