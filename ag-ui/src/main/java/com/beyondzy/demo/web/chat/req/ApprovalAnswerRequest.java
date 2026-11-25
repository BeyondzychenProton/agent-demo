package com.beyondzy.demo.web.chat.req;


import io.swagger.v3.oas.annotations.media.Schema;

/** 审批答复。工具名和参数由服务端从原始请求恢复，这里只接收决定和说明。 */
@Schema(description = "审批答复。")
public record ApprovalAnswerRequest(@Schema(description = "审批决定") String decision, @Schema(description = "答复说明") String reason) {}
