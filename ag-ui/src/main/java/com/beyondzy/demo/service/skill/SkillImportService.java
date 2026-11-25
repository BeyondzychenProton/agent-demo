package com.beyondzy.demo.service.skill;

import com.beyondzy.demo.service.skill.view.SkillImportView;

import com.beyondzy.demo.contract.PageResult;
import java.util.List;

/** 导入批次：上传、检查、映射和确认。确认前不写运行技能。 */
public interface SkillImportService {
    SkillImportView.Created upload(long operatorId, String requestKey, String filename, byte[] body);

    PageResult<SkillImportView.Summary> list(long operatorId, String state, int page, int size);

    SkillImportView get(long operatorId, long id);

    SkillImportView configure(long operatorId, long id, String revision, String targetName, String entryPolicy,
            String contentPolicy, String visibleScope, Long ownerDeptId);

    SkillImportView saveDependencies(long operatorId, long id, String revision, List<DependencyInput> items);

    SkillImportView confirm(long operatorId, long id, String revision, boolean acknowledgeExisting,
            List<String> acknowledgedRuleKeys);

    SkillImportView cancel(long operatorId, long id, String revision);

    SkillImportView.SourceFile source(long operatorId, long id);

    record DependencyInput(
            String sourceKey,
            String targetKind,
            String targetProviderKey,
            String targetToolKey,
            String mappingState,
            String reason) {}
}
