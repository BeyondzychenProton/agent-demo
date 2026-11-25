package com.beyondzy.demo.service.skill.impl;

import com.beyondzy.demo.domain.directory.DirectoryDepartmentBean;
import com.beyondzy.demo.domain.directory.DirectoryUserBean;
import com.beyondzy.demo.domain.skill.SkillBean;
import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.enums.skill.SkillVisibleScope;
import com.beyondzy.demo.mapper.directory.DirectoryDepartmentMapper;
import com.beyondzy.demo.mapper.directory.DirectoryUserMapper;
import com.beyondzy.demo.mapper.skill.AgentSkillMapper;
import com.beyondzy.demo.mapper.user.UserMapper;
import com.beyondzy.demo.service.skill.SkillVisibilityService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 可见范围只看技能行上的三档，不读部门授权表。 */
@Service
public class SkillVisibilityServiceImpl implements SkillVisibilityService {
    private final UserMapper users;
    private final DirectoryUserMapper directoryUsers;
    private final DirectoryDepartmentMapper departments;
    private final AgentSkillMapper bindings;

    public SkillVisibilityServiceImpl(
            UserMapper users,
            DirectoryUserMapper directoryUsers,
            DirectoryDepartmentMapper departments,
            AgentSkillMapper bindings) {
        this.users = users;
        this.directoryUsers = directoryUsers;
        this.departments = departments;
        this.bindings = bindings;
    }

    @Override
    public List<String> namesFor(String agentId, String appUserId) {
        Long userId = positiveUserId(appUserId);
        if (userId == null || viewer(userId) == null) return List.of();
        List<String> names = new ArrayList<>();
        for (SkillBean skill : bindings.listBound(agentId)) {
            if (!SkillBean.STATUS_ENABLED.equals(skill.getStatus())) continue;
            if (visibleTo(skill, userId)) names.add(skill.getName());
        }
        return List.copyOf(names);
    }

    @Override
    public Long departmentIdOf(long appUserId) {
        Viewer viewer = viewer(appUserId);
        return viewer == null ? null : viewer.departmentId();
    }

    @Override
    public boolean departmentActive(long departmentId) {
        return activeDepartment(departmentId);
    }

    @Override
    public boolean visibleTo(SkillBean skill, long appUserId) {
        Viewer viewer = viewer(appUserId);
        if (viewer == null || skill == null) return false;
        String scope = skill.getVisibleScope();
        if (scope == null || scope.isBlank() || SkillVisibleScope.PUBLIC.code().equals(scope)) return true;
        if (SkillVisibleScope.DEPARTMENT.code().equals(scope)) {
            return viewer.departmentId() != null && viewer.departmentId().equals(skill.getOwnerDeptId());
        }
        if (SkillVisibleScope.OWNER.code().equals(scope)) {
            return skill.getCreateBy() != null && skill.getCreateBy().equals(viewer.userId());
        }
        return false;
    }

    /** 不是正整数就当认不出，调用方得到空名单，而不是退回全表。 */
    private static Long positiveUserId(String raw) {
        if (raw == null || raw.isBlank() || !raw.chars().allMatch(Character::isDigit)) return null;
        try {
            long parsed = Long.parseLong(raw);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private Viewer viewer(long appUserId) {
        UserBean user = users.findSkillViewer(appUserId);
        if (user == null || !Objects.equals(user.getIsDeleted(), UserBean.NOT_DELETED)) return null;
        return new Viewer(user.getId(), departmentOf(user.getMantianxingUserId()));
    }

    private Long departmentOf(Long mantianxingUserId) {
        if (mantianxingUserId == null) return null;
        DirectoryUserBean member = directoryUsers.findSkillViewer(mantianxingUserId);
        if (member == null || !Objects.equals(member.getIsDeleted(), DirectoryUserBean.NOT_DELETED)) return null;
        if (!DirectoryUserBean.STATUS_ENABLED.equals(member.getStatus()) || member.getDepartmentId() == null) return null;
        if (!activeDepartment(member.getDepartmentId())) return null;
        return member.getDepartmentId();
    }

    private boolean activeDepartment(long departmentId) {
        DirectoryDepartmentBean department = departments.findSkillViewer(departmentId);
        return department != null
                && Objects.equals(department.getIsDeleted(), DirectoryDepartmentBean.NOT_DELETED)
                && DirectoryDepartmentBean.STATUS_ENABLED.equals(department.getStatus());
    }

    private record Viewer(long userId, Long departmentId) {}
}
