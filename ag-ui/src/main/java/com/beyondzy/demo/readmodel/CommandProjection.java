package com.beyondzy.demo.readmodel;

import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInbox;
import java.util.*;

/** 从双日志投影命令阶段，区分已接收、排队、执行和终态；不重新扫描历史任务。 */
public final class CommandProjection {
    public record Command(String commandId, String turnId, String runId, String status, String reason) {
        public boolean busy() { return Set.of("queued", "running", "finishing").contains(status); }
    }
    public record Checkpoint(List<Command> commands, Map<String, String> runEnds) {}
    private final LinkedHashMap<String, Command> commands = new LinkedHashMap<>();
    private final Map<String, String> runEnds = new HashMap<>();

    public void execution(SessionEvent event) {
        if (event.type().equals("run/end")) runEnds.put(event.executionRunId(), String.valueOf(event.data().get("status")));
    }

    public void inbox(SessionEvent event) {
        switch (event.type()) {
            case "inbox/accepted" -> {
                var command = event.payload(SessionInbox.Command.class);
                if (command.execution()) commands.putIfAbsent(command.id(),
                        new Command(command.id(), command.turnId(), null, "queued", null));
            }
            case "inbox/opened" -> {
                // 同一 turn 的审批续跑会有新命令，仅关联尚未开启执行的命令。
                for (var entry : commands.entrySet()) {
                    var command = entry.getValue();
                    if (Objects.equals(command.turnId(), event.turnId()) && command.status().equals("queued")) {
                        entry.setValue(new Command(command.commandId(), command.turnId(), event.executionRunId(), "running", null));
                        break;
                    }
                }
            }
            case "inbox/closed" -> commands.replaceAll((id, command) ->
                    Objects.equals(command.runId(), event.executionRunId()) && command.busy()
                            ? new Command(id, command.turnId(), command.runId(), "finishing", null) : command);
            case "inbox/rejected" -> {
                String id = String.valueOf(event.data().get("commandId"));
                var command = commands.get(id);
                String reason = String.valueOf(event.data().get("reason"));
                if (reason.codePointCount(0, reason.length()) > 128)
                    reason = reason.substring(0, reason.offsetByCodePoints(0, 128)) + "…";
                if (command != null) commands.put(id, new Command(id, command.turnId(), command.runId(),
                        "rejected", reason));
            }
            default -> { }
        }
    }

    public Checkpoint checkpoint() {
        commands.replaceAll((id, command) -> command.runId() != null && runEnds.containsKey(command.runId())
                && command.busy() ? new Command(id, command.turnId(), command.runId(), runEnds.get(command.runId()), null) : command);
        // 保留所有未完成命令及最近64个已收口回执，避免普通多轮会话使状态体无限增长。
        int settled = (int) commands.values().stream().filter(command -> !command.busy()).count();
        var iterator = commands.values().iterator();
        while (settled > 64 && iterator.hasNext()) if (!iterator.next().busy()) { iterator.remove(); settled--; }
        var runs = new HashSet<String>();
        commands.values().forEach(command -> { if (command.runId() != null) runs.add(command.runId()); });
        runEnds.keySet().retainAll(runs);
        return new Checkpoint(List.copyOf(commands.values()), Map.copyOf(runEnds));
    }

    public void restore(Checkpoint checkpoint) {
        checkpoint.commands().forEach(command -> commands.put(command.commandId(), command));
        runEnds.putAll(checkpoint.runEnds());
    }
}
