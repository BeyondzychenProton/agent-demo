package com.beyondzy.demo.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * 写文件工具：只允许写 workspace/notes 下，返回 added/deleted 供卡片渲染 diff 统计。
 *
 * <p>ChatSessions 的权限规则要求本工具执行前 ASK。审批不在方法内等待，也不由本方法
 * 保存决定；框架收到人工批准后才会进入实际写入。这使页面断线不影响决定的持久化。
 * 此处只负责受控文件写入，不提供崩溃后外部效果恰好一次的承诺。
 */
public final class NoteTools {

    private final Path workspace;

    public NoteTools(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    /** 实际写入在订阅时发生；未通过框架审批时不应进入这段工具执行。 */
    @Tool(
            name = "write_note",
            description =
                    "把结论写入工作区 notes 目录下的 Markdown 文件。用户要求记录、保存、写笔记时调用。"
                            + " 参数 path 形如 notes/compare.md。")
    public Mono<ToolResultBlock> writeNote(
            @ToolParam(name = "path", description = "相对路径，例如 notes/compare.md") String path,
            @ToolParam(name = "content", description = "要写入的 Markdown 内容") String content)
            throws IOException {
        String relative = normalize(path);
        Path target = workspace.resolve(relative).normalize();
        if (!target.startsWith(workspace)) {
            throw new IOException("路径越界：" + path);
        }
        String body = content == null ? "" : content;
        return Mono.fromCallable(
                () -> {
                    String previous =
                            Files.exists(target)
                                    ? Files.readString(target, StandardCharsets.UTF_8)
                                    : "";
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, body, StandardCharsets.UTF_8);

                    int[] stats = LineDiff.stats(previous, body);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("path", relative);
                    result.put("added", stats[0]);
                    result.put("deleted", stats[1]);
                    result.put("lines", body.isEmpty() ? 0 : body.split("\\R", -1).length);
                    return ToolResultBlock.text(JsonUtils.getJsonCodec().toJson(result));
                });
    }

    private static String normalize(String path) throws IOException {
        if (path == null || path.isBlank()) throw new IOException("path 不能为空");
        String value = path.replace('\\', '/').strip();
        if (value.startsWith("/") || value.contains("..")) {
            throw new IOException("只允许写 notes 目录下的相对路径：" + path);
        }
        if (!value.startsWith("notes/")) value = "notes/" + value;
        if (!value.endsWith(".md") && !value.endsWith(".txt")) {
            value = value + ".md";
        }
        return value;
    }
}
