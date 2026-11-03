package com.beyondzy.demo.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * 文档检索工具：不注册前端定制 renderer，验证“未注册工具走默认折叠块”的兜底分支。
 */
public final class DocSearchTools {

    private static final List<Map<String, Object>> DOCS =
            List.of(
                    doc(
                            "AG-UI 事件协议",
                            "AG-UI 用 SSE 把 Agent 事件投影成标准帧：TEXT_MESSAGE、TOOL_CALL、STATE、INTERRUPT。",
                            List.of("ag-ui", "协议", "sse")),
                    doc(
                            "SessionLog 快照与增量",
                            "打开会话先取 committed 快照，再用 cursor 从水位续读增量，保证不漏不重。",
                            List.of("sessionlog", "快照", "增量")),
                    doc(
                            "工具卡片两条来源链路",
                            "实时走 AG-UI 帧，历史走服务端 committed 重建，两条路径产出同一个 ViewModel。",
                            List.of("工具卡片", "viewmodel", "重建")),
                    doc(
                            "Turn 与 Run",
                            "Turn 是逻辑任务，Run 是一次实际执行；HITL 续跑换 runId 不换 turnId。",
                            List.of("turn", "run", "hitl")));

    private static Map<String, Object> doc(String title, String snippet, List<String> tags) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("title", title);
        value.put("snippet", snippet);
        value.put("tags", tags);
        return value;
    }

    @Tool(
            name = "search_docs",
            description = "检索内部技术文档。用户询问设计、协议、会话恢复、工具卡片等概念时调用。")
    public Mono<ToolResultBlock> searchDocs(
            @ToolParam(name = "query", description = "检索关键词，例如“工具卡片”") String query) {
        return Mono.fromCallable(
                () -> {
                    String needle = query == null ? "" : query.toLowerCase();
                    List<Map<String, Object>> matched = new ArrayList<>();
                    for (Map<String, Object> doc : DOCS) {
                        String haystack =
                                (doc.get("title") + " " + doc.get("snippet") + " " + doc.get("tags"))
                                        .toLowerCase();
                        if (needle.isBlank() || haystack.contains(needle)) {
                            matched.add(doc);
                        }
                    }
                    if (matched.isEmpty()) matched.addAll(DOCS);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("query", query);
                    result.put("count", matched.size());
                    result.put("items", matched);
                    return ToolResultBlock.text(JsonUtils.getJsonCodec().toJson(result));
                });
    }
}
