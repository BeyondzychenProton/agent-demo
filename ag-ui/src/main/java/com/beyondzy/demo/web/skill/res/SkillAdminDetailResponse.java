package com.beyondzy.demo.web.skill.res;

import java.time.LocalDateTime;
import java.util.List;

/** 管理端技能详情。content 是纯正文，files 是参考资料。 */
public record SkillAdminDetailResponse(
        String name,
        String description,
        String content,
        String status,
        String visibleScope,
        Long ownerDeptId,
        String ownerDeptName,
        boolean bound,
        LocalDateTime updatedAt,
        List<SkillFileResponse> files) {
    public record SkillFileResponse(String path, String content) {}
}
