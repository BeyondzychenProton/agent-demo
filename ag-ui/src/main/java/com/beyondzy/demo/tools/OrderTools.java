package com.beyondzy.demo.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * 订单查询工具：前端用 {@code useRenderTool({name:"lookup_order"})} 注册定制订单卡。
 *
 * <p>执行中通过 {@link ToolEmitter} 发进度，进度事件会落成 {@code tool/chunk}，
 * 用于验证大纲 6.1.9“进度进入卡片”的可行性边界。
 */
public final class OrderTools {

    private static final Map<String, Map<String, Object>> ORDERS = orders();

    private static Map<String, Map<String, Object>> orders() {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        all.put("A1001", order("A1001", "已发货", "顺丰", "SF123456789", "2026-11-03", 598.0));
        all.put("A1002", order("A1002", "待发货", "京东物流", "-", "2026-11-05", 1299.0));
        return Map.copyOf(all);
    }

    private static Map<String, Object> order(
            String id, String status, String carrier, String tracking, String eta, double total) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("orderId", id);
        value.put("customer", "海卓科技");
        value.put("status", status);
        value.put("carrier", carrier);
        value.put("trackingNo", tracking);
        value.put("eta", eta);
        value.put("total", total);
        value.put(
                "items",
                List.of(
                        Map.of("sku", "AG-UI 企业 Agent 课程包", "qty", 2, "price", total / 2)));
        return value;
    }

    @Tool(
            name = "lookup_order",
            description =
                    "查询订单的物流与金额信息。用户提到订单号、发货状态、快递、到货时间时调用。"
                            + " 参数 order_id 形如 A1001。")
    public Mono<ToolResultBlock> lookupOrder(
            @ToolParam(name = "order_id", description = "订单号，例如 A1001") String orderId,
            ToolEmitter emitter) {
        return Mono.fromCallable(
                () -> {
                    emitter.emit(ToolResultBlock.text("正在查询订单 " + orderId + " …"));
                    Map<String, Object> found = ORDERS.get(orderId);
                    if (found == null) {
                        return ToolResultBlock.text(
                                JsonUtils.getJsonCodec()
                                        .toJson(Map.of("error", "未找到订单 " + orderId)));
                    }
                    emitter.emit(ToolResultBlock.text("已命中订单，整理物流信息 …"));
                    return ToolResultBlock.text(JsonUtils.getJsonCodec().toJson(found));
                });
    }
}
