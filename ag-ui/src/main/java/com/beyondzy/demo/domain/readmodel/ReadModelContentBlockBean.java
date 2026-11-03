package com.beyondzy.demo.domain.readmodel;

/**
 * 内容分块查询投影（非单表行模型）：body 来自 rm_block，encoding 来自 rm_content。
 * 因跨表 JOIN，不加 @TableName。
 */
public class ReadModelContentBlockBean {
    private String body;
    private String encoding;
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getEncoding() { return encoding; }
    public void setEncoding(String encoding) { this.encoding = encoding; }
}
