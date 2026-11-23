package com.beyondzy.demo.service.skill.impl;

import com.beyondzy.demo.domain.directory.DirectoryDepartmentBean;
import com.beyondzy.demo.domain.directory.DirectoryUserBean;
import com.beyondzy.demo.domain.skill.SkillBean;
import com.beyondzy.demo.domain.user.UserBean;
import com.beyondzy.demo.mapper.directory.DirectoryDepartmentMapper;
import com.beyondzy.demo.mapper.directory.DirectoryUserMapper;
import com.beyondzy.demo.mapper.skill.AgentSkillMapper;
import com.beyondzy.demo.mapper.user.UserMapper;
import com.beyondzy.demo.service.skill.SkillVisibilityService;
import java.util.ArrayList;
import java.util.List;
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
            if (!"0".equals(skill.getStatus())) continue;
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
        if (scope == null || scope.isBlank() || "0".equals(scope)) return true;
        if ("1".equals(scope)) {
            return viewer.departmentId() != null && viewer.departmentId().equals(skill.getOwnerDeptId());
        }
        if ("2".equals(scope)) {
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
        if (user == null || user.getIsDeleted() == null || user.getIsDeleted() != 0) return null;
        return new Viewer(user.getId(), departmentOf(user.getMantianxingUserId()));
    }

    private Long departmentOf(Long mantianxingUserId) {
        if (mantianxingUserId == null) return null;
        DirectoryUserBean member = directoryUsers.findSkillViewer(mantianxingUserId);
        if (member == null || member.getIsDeleted() == null || member.getIsDeleted() != 0) return null;
        if (!"0".equals(member.getStatus()) || member.getDepartmentId() == null) return null;
        if (!activeDepartment(member.getDepartmentId())) return null;
        return member.getDepartmentId();
    }

    private boolean activeDepartment(long departmentId) {
        DirectoryDepartmentBean department = departments.findSkillViewer(departmentId);
        return department != null
                && department.getIsDeleted() != null
                && department.getIsDeleted() == 0
                && "0".equals(department.getStatus());
    }

    private record Viewer(long userId, Long departmentId) {}
}
