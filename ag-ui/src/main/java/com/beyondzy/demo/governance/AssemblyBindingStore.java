package com.beyondzy.demo.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 持久化「会话未收口任务 → snapshotId」绑定，供进程重启后重建原装配。
 *
 * <p>文件位于工作区 {@code .governance-assembly-bindings.json}；写失败不阻断主路径，但恢复时会失败。
 */
public final class AssemblyBindingStore {

    private static final Logger logger = LoggerFactory.getLogger(AssemblyBindingStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {};

    private final Path file;
    private final ConcurrentHashMap<String, String> bindings = new ConcurrentHashMap<>();

    public AssemblyBindingStore(Path workspace) {
        this.file = workspace.resolve(".governance-assembly-bindings.json");
        load();
    }

    public void bind(String sessionKey, String snapshotId) {
        if (sessionKey == null || sessionKey.isBlank() || snapshotId == null || snapshotId.isBlank()) return;
        bindings.put(sessionKey, snapshotId);
        persist();
    }

    public void clear(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) return;
        if (bindings.remove(sessionKey) != null) persist();
    }

    /**
     * 仅当当前绑定仍是提交时记下的 snapshotId 时才清除。
     * 过期后台回调不得清掉后续任务写下的绑定。
     */
    public boolean clearIfMatch(String sessionKey, String snapshotId) {
        if (sessionKey == null || sessionKey.isBlank() || snapshotId == null || snapshotId.isBlank()) {
            return false;
        }
        boolean removed = bindings.remove(sessionKey, snapshotId);
        if (removed) persist();
        return removed;
    }

    public String snapshotId(String sessionKey) {
        return sessionKey == null ? null : bindings.get(sessionKey);
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            Map<String, String> loaded = JSON.readValue(Files.readString(file), MAP);
            if (loaded != null) bindings.putAll(loaded);
        } catch (IOException error) {
            logger.warn("读取装配绑定失败 {}: {}", file, error.toString());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(bindings));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            logger.warn("写入装配绑定失败 {}: {}", file, error.toString());
        }
    }
}
