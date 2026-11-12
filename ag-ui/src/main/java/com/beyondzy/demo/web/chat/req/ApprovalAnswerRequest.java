package com.beyondzy.demo.web.chat.req;

/** 审批答复。工具名和参数由服务端从原始请求恢复，这里只接收决定和说明。 */
public record ApprovalAnswerRequest(String decision, String reason) {}
