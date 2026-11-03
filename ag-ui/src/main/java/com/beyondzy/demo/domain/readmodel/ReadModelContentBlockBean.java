package com.beyondzy.demo.domain.readmodel;

/** 内容分块查询结果：块正文与编码。对应表：rm_content JOIN rm_block。 */
public class ReadModelContentBlockBean {
    private String body;
    private String encoding;
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getEncoding() { return encoding; }
    public void setEncoding(String encoding) { this.encoding = encoding; }
}
