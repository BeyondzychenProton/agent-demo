package com.beyondzy.demo.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户工作空间目录。
 *
 * <p>每个用户在磁盘上有一个独立目录：{@code <root>/<userId>/}。
 * 目录本身由框架的 {@code IsolationScope.USER} 决定（同一 userId 的读写都落在这一桶内），
 * 本类只负责把目录"提前建出来"，让运维和后续功能（笔记/记忆/上传文件）有确定的落点。
 *
 * <p>为什么角色是"建目录"而不是"管文件"：当前迭代所有文件工具仍关闭，
 * 目录是隔离骨架；等开启 read_file/write_file 时，框架会自动把相对路径解析到这里。
 *
 * <p>userId 只允许 {@code [A-Za-z0-9_-]}：它来自登录态，但仍然防一手路径穿越。
 */
public class UserWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(UserWorkspaceService.class);
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    private final Path root;
    private final Set<String> ensured = ConcurrentHashMap.newKeySet();

    public UserWorkspaceService(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** 工作区根目录（框架 workspace 指向这里）。 */
    public Path root() {
        return root;
    }

    /** 某个用户的工作空间目录。 */
    public Path dirOf(String userId) {
        if (userId == null || !SAFE_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("非法的用户 id: " + userId);
        }
        return root.resolve(userId);
    }

    /**
     * 确保用户目录存在（首次调用建目录，进程内幂等）。
     *
     * @return 用户目录
     */
    public Path ensure(String userId) {
        Path dir = dirOf(userId);
        if (ensured.contains(userId)) {
            return dir;
        }
        try {
            Files.createDirectories(dir);
            ensured.add(userId);
        } catch (IOException error) {
            // 建目录失败不应阻断对话：框架在该用户首次写文件时还会再尝试
            log.warn("创建用户工作空间失败 userId={} dir={}", userId, dir, error);
        }
        return dir;
    }
}
