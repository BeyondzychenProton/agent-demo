package com.beyondzy.demo.session;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 按官方本机 Filesystem 把会话日志放进每个用户自己的工作区。
 *
 * <p>{@link IsolationScope#USER} 把相对路径加到 {@code userId} 前缀上。
 * {@link WorkspaceSessionLogStore} 再把原生日志写到该身份解析出的根目录下
 * {@code .agentscope-runtime/}。因此用户 {@code 7} 的日志在
 * {@code <workspace>/7/.agentscope-runtime/}，不会写进工作区根目录。
 */
final class UserWorkspaceLog {
    private final Path root;
    private final AbstractFilesystem filesystem;
    private final WorkspaceSessionLogStore store;

    UserWorkspaceLog(Path workspace) {
        try {
            this.root = Files.createDirectories(workspace.toAbsolutePath().normalize());
        } catch (IOException error) {
            throw new UncheckedIOException("无法创建工作区 " + workspace, error);
        }
        this.filesystem = new LocalFilesystemSpec()
                .isolationScope(IsolationScope.USER)
                .toFilesystem(root, IsolationScope.USER.toNamespaceFactory());
        this.store = new WorkspaceSessionLogStore(filesystem);
    }

    Path root() {
        return root;
    }

    AbstractFilesystem filesystem() {
        return filesystem;
    }

    WorkspaceSessionLogStore store() {
        return store;
    }

    /** 已经写过原生日志的用户目录。空工作区返回空列表。 */
    List<String> userIds() {
        try (Stream<Path> children = Files.list(root)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(".agentscope-runtime")))
                    .map(dir -> dir.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException("无法列用户工作区 " + root, error);
        }
    }
}
