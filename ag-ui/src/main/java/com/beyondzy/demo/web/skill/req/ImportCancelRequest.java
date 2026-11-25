package com.beyondzy.demo.web.skill.req;


import io.swagger.v3.oas.annotations.media.Schema;

/** 取消尚未确认的导入批次。 */
@Schema(description = "取消导入批次。")
public record ImportCancelRequest(@Schema(description = "乐观锁版本号") String revision) {}
