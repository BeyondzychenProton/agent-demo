package com.beyondzy.demo.service.directory.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.beyondzy.demo.contract.directory.DirectoryPush;
import com.beyondzy.demo.domain.directory.DirectoryDepartmentBean;
import com.beyondzy.demo.domain.directory.DirectoryPushHistoryBean;
import com.beyondzy.demo.domain.directory.DirectoryUserBean;
import com.beyondzy.demo.mapper.directory.DirectoryDepartmentMapper;
import com.beyondzy.demo.mapper.directory.DirectoryPushHistoryMapper;
import com.beyondzy.demo.mapper.directory.DirectoryUserMapper;
import com.beyondzy.demo.service.directory.DirectoryService;
import com.beyondzy.demo.service.user.UserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Objects;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 组织通讯录服务实现，负责版本判断和跨表事务。 */
@Service
public class DirectoryServiceImpl implements DirectoryService {
    private static final ObjectMapper HISTORY_JSON = new ObjectMapper();

    private final DirectoryDepartmentMapper departments;
    private final DirectoryUserMapper directoryUsers;
    private final DirectoryPushHistoryMapper history;
    private final UserService users;

    public DirectoryServiceImpl(DirectoryDepartmentMapper departments, DirectoryUserMapper directoryUsers,
                                DirectoryPushHistoryMapper history, UserService users) {
        this.departments = departments;
        this.directoryUsers = directoryUsers;
        this.history = history;
        this.users = users;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void apply(DirectoryPush push) {
        // 批次内按来源 ID 排序写入，减少并发推送时的锁顺序冲突。
        push.departments().stream().sorted(Comparator.comparing(DirectoryPush.Department::deptId))
                .forEach(this::applyDepartment);
        push.users().stream().sorted(Comparator.comparing(DirectoryPush.Member::userId))
                .forEach(this::applyUser);
        // 成功历史与通讯录、账号变更一并提交。
        recordPush(push, PushStatus.ACCEPTED);
    }

    private void applyDepartment(DirectoryPush.Department change) {
        boolean deleted = change.deleted();
        var row = new DirectoryDepartmentBean();
        row.setSourceId(change.deptId());
        row.setSourceVersion(change.version());
        row.setParentId(deleted ? null : optionalId(change.parentId()));
        row.setName(deleted ? null : change.deptName());
        row.setSortOrder(deleted || change.orderNum() == null ? 0 : change.orderNum());
        row.setStatus(deleted ? null : change.status());
        row.setIsDeleted(deleted ? DirectoryDepartmentBean.DELETED : DirectoryDepartmentBean.NOT_DELETED);
        departments.upsert(row);
    }

    private void applyUser(DirectoryPush.Member change) {
        // 锁定当前版本，防止旧事件更新本地登录账号。
        var current = directoryUsers.selectOne(Wrappers.<DirectoryUserBean>lambdaQuery()
                .select(DirectoryUserBean::getSourceVersion)
                .eq(DirectoryUserBean::getSourceId, change.userId())
                .last("FOR UPDATE"));
        if (current != null && current.getSourceVersion() >= change.version()) return;

        boolean deleted = change.deleted();
        if (deleted) {
            users.disableDirectoryAccount(change.userId());
        } else {
            users.syncDirectoryAccount(change.userId(), change.nickName(), change.phonenumber().strip(),
                    DirectoryUserBean.STATUS_ENABLED.equals(change.status()));
        }

        var row = new DirectoryUserBean();
        row.setSourceId(change.userId());
        row.setSourceVersion(change.version());
        row.setDepartmentId(deleted ? null : optionalId(change.deptId()));
        row.setAccount(deleted ? null : change.userName());
        row.setName(deleted ? null : change.nickName());
        row.setEmail(deleted ? null : change.email());
        row.setStatus(deleted ? null : change.status());
        row.setIsDeleted(deleted ? DirectoryUserBean.DELETED : DirectoryUserBean.NOT_DELETED);
        directoryUsers.upsert(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordPush(DirectoryPush push, PushStatus status) {
        var row = new DirectoryPushHistoryBean();
        row.setSource("MANTIANXING");
        row.setStatus(status.name());
        row.setDepartmentCount(push == null ? 0 : push.departments().size());
        row.setUserCount(push == null ? 0 : push.users().size());
        row.setPayload(push == null ? null : historyPayload(push));
        history.insert(row);
    }

    private static String historyPayload(DirectoryPush push) {
        try {
            return HISTORY_JSON.writeValueAsString(push);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize directory push", ex);
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Directory read() {
        var departmentRows = departments.selectList(Wrappers.<DirectoryDepartmentBean>lambdaQuery()
                .eq(DirectoryDepartmentBean::getIsDeleted, DirectoryDepartmentBean.NOT_DELETED)
                .orderByAsc(DirectoryDepartmentBean::getSortOrder, DirectoryDepartmentBean::getSourceId));
        var userRows = directoryUsers.selectList(Wrappers.<DirectoryUserBean>lambdaQuery()
                .eq(DirectoryUserBean::getIsDeleted, DirectoryUserBean.NOT_DELETED)
                .orderByAsc(DirectoryUserBean::getName, DirectoryUserBean::getSourceId));
        return new Directory(
                departmentRows.stream().map(row -> new Department(row.getSourceId().toString(),
                        asString(row.getParentId()), row.getName(), row.getStatus())).toList(),
                userRows.stream().map(row -> new Member(row.getSourceId().toString(),
                        asString(row.getDepartmentId()), row.getAccount(), row.getName(),
                        row.getEmail(), row.getStatus())).toList());
    }

    private static Long optionalId(Long id) {
        return id == null || Objects.equals(id, 0L) ? null : id;
    }

    private static String asString(Long id) {
        return id == null ? null : id.toString();
    }
}
