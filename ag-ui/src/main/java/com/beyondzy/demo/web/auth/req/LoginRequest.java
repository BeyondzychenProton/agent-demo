package com.beyondzy.demo.web.auth.req;


import io.swagger.v3.oas.annotations.media.Schema;

/** 登录请求。 */
@Schema(description = "登录请求。")
public record LoginRequest(@Schema(description = "手机号") String phone, @Schema(description = "密码") String password) {}
