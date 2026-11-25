package com.beyondzy.demo.service.skill;

import com.beyondzy.demo.service.skill.view.SkillManagementView;
import com.beyondzy.demo.service.skill.view.SkillVersionView;

/** 页面所需目录、当前生效信息和草稿信息，版本正文仍由版本服务维护。 */
public interface SkillManagementService {
    SkillManagementView.Page page(String query, String state, String resource, int page, int size);

    SkillManagementView.Overview overview(String name);

    SkillVersionView edit(long operatorId, String name);
}
