package com.beyondzy.demo.session;

import com.beyondzy.demo.contract.ThreadView;
import io.agentscope.core.message.Msg;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInbox;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.session.SessionRecovery;
import io.agentscope.core.session.SessionTurns;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.session.AgentSession;
import io.agentscope.harness.agent.session.SessionAnswer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 人工审批应用服务：接收人的决定，并把两类持久事实组装为可恢复的页面状态。
 *
 * <p>阅读顺序：answer 接收决定 → 框架 respond 写 Inbox 并调度 → interactions 读双日志
 * → project 推导状态 → decorate 合并工具卡片。取消走 cancel，不伪造批准或拒绝。
 * 执行日志记录“已经发生什么”，Inbox 记录“系统收下什么输入”；两者不能互相代替。
 * 本服务不直接执行工具、不自行改 checkpoint，也不另建审批表。
 *
 * <p>同会话接收协调仅覆盖单个服务实例；多实例部署需要共享协调。
 */
public final class HitlService {
    private final Object[] locks = new Object[64];

    public HitlService() {
        Arrays.setAll(locks, i -> new Object());
    }

    /**
     * 串行协调同会话的“重读事实—判断冲突—持久接收”，与等待期间取消共用。
     *
     * <p>固定锁条带避免会话数量增长导致锁对象永久累积。不同会话偶尔共享条带只影响并发度。
     * 不持锁等待 Agent 或工具完成；框架内部写入租约仍独立保护实际执行与日志提交。
     */
    public <T> T coordinated(String sessionId, Supplier<T> work) {
        synchronized (locks[Math.floorMod(sessionId.hashCode(), locks.length)]) {
            return work.get();
        }
    }

    /**
     * 一条请求的完整展示依据；call 只从服务端原始请求读取，不接受浏览器重建。
     */
    public record Interaction(String requestId, String turnId, ToolUseBlock call,
                              ThreadView.HitlBlock hitl) {
    }

    /**
     * 接收回执：created 区分新接收与查回；status 是命令进度，不是外部操作成功证明。
     */
    public record AnswerReceipt(boolean created, String requestId, String commandId,
                                String turnId, String runId, String status) {
    }

    /**
     * 读取执行日志和 Inbox 的已提交前缀，并根据执行租约校正可操作性。
     *
     * <p>分别记录两个水位，读取后有变化则重试；这不是跨日志数据库事务，也不保证返回后状态不变。
     * 提交决定时必须重新校验。连续四次读不稳则要求重查，不猜测成“尚未答复”。
     * 已启动却失去有效租约的命令显示需要核对，不自动重放可能已有外部效果的操作。
     */
    public Map<String, Interaction> interactions(AgentSession session) {
        SessionLog log = session.log();
        SessionLog inbox = log.inbox();
        for (int attempt = 0; attempt < 4; attempt++) {
            long seq = log.head().seq(), inputSeq = inbox.head().seq();
            List<SessionEvent> facts = new ArrayList<>(), inputs = new ArrayList<>();
            log.scan(0, seq).forEach(facts::add);
            inbox.scan(0, inputSeq).forEach(inputs::add);
            Map<String, Interaction> view = project(facts, inputs);
            if (seq == log.head().seq() && inputSeq == inbox.head().seq()) {
                var head = log.head();
                boolean active = head.owner() != null && head.leaseUntil() > System.currentTimeMillis();
                var started = new HashMap<String, String>();
                for (var fact : facts)
                    if (fact.type().equals("inbox/started"))
                        started.put((String) fact.data().get("commandId"), fact.executionRunId());
                var adjusted = new LinkedHashMap<String, Interaction>();
                view.forEach((id, i) -> {
                    var h = i.hitl();
                    String status = h.interactionStatus();
                    String warning = h.unavailableReason();
                    boolean canRespond = h.canRespond();
                    if (active && status.equals("pending")) {
                        canRespond = false;
                        warning = "当前执行正在收尾，请稍候";
                    }
                    // 命令已经启动却不再持有有效执行租约，不能一直伪报正常处理中。
                    String run = started.get(h.commandId());
                    if (status.equals("processing") && run != null && (!active || !run.equals(head.owner()))) {
                        status = "reconciling";
                        canRespond = false;
                        warning = "答复处理曾中断，需要核对执行事实";
                    }
                    h = new ThreadView.HitlBlock(h.requestId(), h.askPrompt(), h.payload(), h.decision(),
                            h.decideAt(), h.auditRef(), status, h.reason(), h.commandId(), h.continuationRunId(), canRespond, warning);
                    adjusted.put(id, new Interaction(i.requestId(), i.turnId(), i.call(), h));
                });
                return Collections.unmodifiableMap(adjusted);
            }
        }
        throw new IllegalStateException("交互状态正在更新，请稍后重新查询");
    }

    /**
     * 纯事实投影：以原始请求为骨架，关联有效答复命令，再合并已解决结果。
     *
     * <p>优先级：任务取消 / 已解决事实 ＞ Inbox 已接收 ＞ 原始待答。
     * 先排除被执行侧拒绝的命令；命令被拒绝不等于用户选择拒绝操作。
     * 处理后的请求继续保留，支持刷新后查阅批准内容、拒绝原因或取消结果。
     * 只处理 confirmation，不将外部结果或信息补充冒充审批。
     */
    public static Map<String, Interaction> project(List<SessionEvent> facts, List<SessionEvent> inputs) {
        Map<String, SessionEvent> requests = new LinkedHashMap<>(), resolved = new HashMap<>();
        Set<String> cancelled = new HashSet<>(), rejected = new HashSet<>();
        Map<String, String> commandRuns = new HashMap<>(), runEnds = new HashMap<>();
        for (var event : facts) {
            if (event.type().equals("interaction/requested") && "confirmation".equals(event.data().get("kind")))
                requests.put((String) event.data().get("requestId"), event);
            if (event.type().equals("interaction/resolved"))
                resolved.put((String) event.data().get("requestId"), event);
            if (event.type().equals("turn/cancelled")) cancelled.add(event.turnId());
            if (event.type().equals("inbox/started"))
                commandRuns.put((String) event.data().get("commandId"), event.executionRunId());
            if (event.type().equals("run/end"))
                runEnds.put(event.executionRunId(), (String) event.data().get("status"));
        }
        for (var event : inputs)
            if (event.type().equals("inbox/rejected"))
                rejected.add((String) event.data().get("commandId"));
        Map<String, SessionInbox.Command> answers = new HashMap<>();
        for (var event : inputs)
            if (event.type().equals("inbox/accepted")) {
                var command = event.payload(SessionInbox.Command.class);
                if (command.kind().equals("respond") && !rejected.contains(command.id()))
                    for (String id : command.requestIds()) answers.putIfAbsent(id, command);
            }
        Map<String, Interaction> result = new LinkedHashMap<>();
        for (var entry : requests.entrySet()) {
            String id = entry.getKey();
            var request = entry.getValue();
            var call = JsonUtils.getJsonCodec().convertValue(request.data().get("call"), ToolUseBlock.class);
            var command = answers.get(id);
            var resolution = resolved.get(id);
            ConfirmResult decision = command == null ? null : confirmation(command, id);
            String state = command == null ? "pending" : "processing";
            String value = decision == null ? null : decision.isConfirmed() ? "approved" : "rejected";
            String reason = decision == null ? null : decision.getReason();
            if (command != null && (decision == null || runEnds.containsKey(commandRuns.get(command.id()))))
                state = "reconciling"; // 执行已结束却没有确认事实，不能冒充仍在正常处理。
            // resolved 也可能代表取消，不能一概翻译成“已批准”。
            if (resolution != null) {
                if ("cancelled".equals(resolution.data().get("status"))) state = "cancelled";
                else if (resolution.data().get("result") != null) {
                    decision = JsonUtils.getJsonCodec().convertValue(resolution.data().get("result"), ConfirmResult.class);
                    state = decision.isConfirmed() ? "approved" : "rejected";
                    value = state;
                    reason = decision.getReason();
                } else state = "reconciling";
            }
            if (cancelled.contains(request.turnId())) state = "cancelled";
            boolean canRespond = state.equals("pending");
            var hitl = new ThreadView.HitlBlock(id, "请确认是否执行：" + call.getName(), call.getInput(),
                    value, resolution == null ? null : Instant.ofEpochMilli(resolution.occurredAt()).toString(),
                    request.eventId(), state, reason, command == null ? null : command.id(),
                    command == null ? null : commandRuns.get(command.id()), canRespond,
                    canRespond ? null : state.equals("cancelled") ? "任务已取消" : "该请求已有处理记录，请查看当前状态");
            result.put(id, new Interaction(id, request.turnId(), call, hitl));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 展示增量投影复用事实规则，再用当前租约修正按钮；提交时仍走完整权威复核。
     */
    public static Map<String, Interaction> projectForDisplay(List<SessionEvent> facts,
                                                             List<SessionEvent> inputs, SessionLog.Head head) {
        var started = new HashMap<String, String>();
        for (var fact : facts)
            if (fact.type().equals("inbox/started"))
                started.put((String) fact.data().get("commandId"), fact.executionRunId());
        boolean active = head.owner() != null && head.leaseUntil() > System.currentTimeMillis();
        var result = new LinkedHashMap<String, Interaction>();
        project(facts, inputs).forEach((id, interaction) -> {
            var h = interaction.hitl();
            String status = h.interactionStatus(), warning = h.unavailableReason();
            boolean canRespond = h.canRespond();
            if (active && status.equals("pending")) {
                canRespond = false;
                warning = "当前执行正在收尾，请稍候";
            }
            String run = started.get(h.commandId());
            if (status.equals("processing") && run != null && (!active || !run.equals(head.owner()))) {
                status = "reconciling";
                canRespond = false;
                warning = "答复处理曾中断，需要核对执行事实";
            }
            var adjusted = new ThreadView.HitlBlock(h.requestId(), h.askPrompt(), h.payload(), h.decision(),
                    h.decideAt(), h.auditRef(), status, h.reason(), h.commandId(), h.continuationRunId(), canRespond, warning);
            result.put(id, new Interaction(id, interaction.turnId(), interaction.call(), adjusted));
        });
        return Collections.unmodifiableMap(result);
    }

    /**
     * Inbox 冻结保存消息；反序列化 metadata 后按工具调用 ID 找回原来的决定。
     */
    private static ConfirmResult confirmation(SessionInbox.Command command, String requestId) {
        for (Msg message : command.messages()) {
            Object values = message.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
            if (values instanceof List<?> list) for (Object value : list) {
                var result = JsonUtils.getJsonCodec().convertValue(value, ConfirmResult.class);
                if (result.getToolCall() != null && requestId.equals(result.getToolCall().getId())) return result;
            }
        }
        return null;
    }

    /**
     * 接收单条批准或拒绝。浏览器只提交请求编号、决定及说明，不提交工具调用参数。
     *
     * <p>必须先查已有答复，再调用 respond：处理完的请求可能已不在 pending 中，
     * 直接重调框架不能实现查回。相同决定返回已有回执，不同决定报冲突；说明也参与比较。
     * respond 返回前已尝试持久接收，异常不能证明接收失败，所以结果不明时要求查询，
     * 既不执行补偿写入，也不立即接受相反决定。
     */
    public AnswerReceipt answer(AgentSession session, String requestId, String decision, String reason) {
        if (!Set.of("approve", "reject").contains(decision == null ? "" : decision))
            throw new IllegalArgumentException("决定必须为 approve 或 reject");
        String normalized = reason == null ? "" : reason.strip();
        if (normalized.length() > 2000) throw new IllegalArgumentException("说明不能超过 2000 字");
        return coordinated(session.sessionId(), () -> {
            Interaction current = interactions(session).get(requestId);
            if (current == null) throw new IllegalArgumentException("未找到该审批请求");
            var h = current.hitl();
            if (h.interactionStatus().equals("cancelled") || h.interactionStatus().equals("reconciling"))
                throw new IllegalStateException("该请求已取消或需要核对，请重新查询");
            String expected = decision.equals("approve") ? "approved" : "rejected";
            if (h.commandId() != null || h.decision() != null) {
                if (!expected.equals(h.decision()) || !normalized.equals(h.reason() == null ? "" : h.reason()))
                    throw new IllegalStateException("该请求已有不同答复，请重新查询当前状态");
                if (h.commandId() == null) throw new IllegalStateException("已有处理结果，请查询审批记录");
                return receipt(false, requestId, session.task(h.commandId()));
            }
            var answer = new SessionAnswer.Approval(decision.equals("approve"), normalized);
            try {
                return receipt(true, requestId, session.respond(requestId, answer));
            } catch (SessionLogException | IllegalStateException error) {
                throw new IllegalStateException("暂时无法确认答复接收结果，请查询当前状态后再操作", error);
            }
        });
    }

    /**
     * 保留框架实际观察到的进度；命令可能已开始，因此不把新回执固定写为 queued。
     */
    private static AnswerReceipt receipt(boolean created, String id, AgentSession.Task task) {
        return new AnswerReceipt(created, id, task.commandId(), task.turnId(), task.runId(), task.status());
    }

    /**
     * 取消当前仍在等待、尚未接收答复的整个 turn，与答复提交共用协调。
     *
     * <p>原生 cancelPending 负责请求解决、工具中断结果、checkpoint 和 turn/cancelled 的提交。
     * 若调度已占用执行租约或工具效果不确定，由框架拒绝安全取消；不能仅隐藏页面按钮。
     * 重复取消返回已保存结果；不使用 close/interrupt 冒充任务取消。
     */
    public Map<String, String> cancel(AgentSession session, String turnId) {
        return coordinated(session.sessionId(), () -> {
            var turn = SessionTurns.read(session.log()).latest();
            if (turn == null || !turn.turnId().equals(turnId)) throw new IllegalStateException("只能取消当前待答任务");
            if (turn.status().equals("cancelled")) return Map.of("turnId", turnId, "status", "cancelled");
            var related = interactions(session).values().stream().filter(i -> i.turnId().equals(turnId)).toList();
            if (related.isEmpty() || related.stream().anyMatch(i -> !i.hitl().interactionStatus().equals("pending")))
                throw new IllegalStateException("任务已接收答复或不再等待审批，不能按待答方式取消");
            try {
                SessionRecovery.cancelPending(session.log(), turnId);
            } catch (SessionLogException error) {
                throw new IllegalStateException("暂时无法安全取消，请重新查询执行状态", error);
            }
            return Map.of("turnId", turnId, "status", "cancelled");
        });
    }

    /**
     * 将交互维度合并到原工具卡片，保留执行结果与审批轨迹。
     *
     * <p>等待及接收中的请求保持 suspended；拒绝或取消显示 interrupted。
     * 批准之后保留原执行状态，因此允许出现“已批准、执行失败”。不将审批状态塞进工具七态。
     */
    public ThreadView.SnapshotResponse decorate(ThreadView.SnapshotResponse snapshot, Map<String, Interaction> interactions) {
        var items = snapshot.view().stream().map(item -> {
            if (!(item instanceof ThreadView.ToolCardViewModel card)) return item;
            Interaction interaction = interactions.get(card.toolCallId());
            if (interaction == null) return item;
            var h = interaction.hitl();
            String status = h.interactionStatus().equals("cancelled") || h.interactionStatus().equals("rejected")
                    ? "interrupted" : h.interactionStatus().equals("pending") || h.interactionStatus().equals("processing")
                    ? "suspended" : card.status();
            return (ThreadView.ChatItem) new ThreadView.ToolCardViewModel(card.kind(), card.toolCallId(), card.turnId(),
                    card.runId(), card.name(), card.displayTitle(), status, card.arguments(), card.result(), card.error(),
                    card.progress(), card.diffStats(), card.evictionRef(), h, card.startedAt(), card.endedAt(), card.durationMs());
        }).toList();
        return new ThreadView.SnapshotResponse(snapshot.threadId(), items, snapshot.pendingInteractions(),
                snapshot.asOfSeq(), snapshot.cursor(), snapshot.activeRun());
    }
}
