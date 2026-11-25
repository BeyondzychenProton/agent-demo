package com.beyondzy.demo.readmodel;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 内容分块查询投影（非单表行模型）：body 来自 rm_block，encoding 来自 rm_content。
 * 因跨表 JOIN，不加 @TableName。
 */
@Schema(description = "内容分块查询投影（非单表行模型）：body 来自 rm_block，encoding 来自 rm_content。")
public class ReadModelContentBlockBean {
    @Schema(description = "内容块文本")
    private String body;
    @Schema(description = "内容块编码方式")
    private String encoding;
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getEncoding() { return encoding; }
    public void setEncoding(String encoding) { this.encoding = encoding; }
}
