package com.beyondzy.demo.tools;

import java.util.Map;

/**
 * 工具元数据（M7 ToolCatalog 的最小实现）。
 *
 * <p>工具注册与卡片注册是两处注册：这里只描述“折叠标题怎么拼、是否需要前端定制卡片”，
 * 不承担工具执行，也不承担前端渲染。
 */
public final class ToolCatalog {

    /** 一个工具的展示元数据。 */
    public record ToolMeta(String name, String label, boolean customCard, boolean fileTool) {}

    private static final Map<String, ToolMeta> TOOLS =
            Map.of(
                    "lookup_order",
                    new ToolMeta("lookup_order", "查询订单", true, false),
                    "search_docs",
                    new ToolMeta("search_docs", "检索文档", false, false),
                    "write_note",
                    new ToolMeta("write_note", "写入笔记", false, true));

    private ToolCatalog() {}

    public static ToolMeta of(String name) {
        return TOOLS.getOrDefault(name, new ToolMeta(name, name, false, false));
    }

    public static boolean customCard(String name) {
        return of(name).customCard();
    }

    /**
     * 生成折叠态描述标题，例如“查询订单 A1001”。
     *
     * <p>参数不完整时退化为工具中文名，避免卡片标题出现 {@code null}。
     */
    public static String displayTitle(String name, Map<String, Object> arguments, int added, int deleted) {
        ToolMeta meta = of(name);
        Object value = null;
        if (arguments != null) {
            value =
                    switch (name) {
                        case "lookup_order" -> arguments.get("order_id");
                        case "search_docs" -> arguments.get("query");
                        case "write_note" -> arguments.get("path");
                        default -> null;
                    };
        }
        if (value instanceof String text && !text.isBlank()) {
            return meta.label() + " " + text;
        }
        if (meta.fileTool() && (added > 0 || deleted > 0)) {
            return meta.label() + " +" + added + " -" + deleted;
        }
        return meta.label();
    }
}
