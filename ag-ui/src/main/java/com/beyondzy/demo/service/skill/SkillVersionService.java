package com.beyondzy.demo.service.skill;

import com.beyondzy.demo.service.skill.view.SkillVersionView;

import com.beyondzy.demo.contract.PageResult;
import java.util.List;

/** 草稿编辑和发布。发布会重做检查，校验接口的结果不能当成发布许可。 */
public interface SkillVersionService {
    SkillVersionView create(long operatorId, CreateRequest request);

    PageResult<SkillVersionView.Summary> list(String skillName, String state, int page, int size);

    SkillVersionView get(long id);

    SkillVersionView update(long operatorId, long id, UpdateRequest request);

    SkillVersionView.FileBody file(long versionId, long fileId, boolean download);

    SkillVersionView.Validation validate(long id, String revision);

    SkillVersionView.Published publish(long operatorId, long id, PublishRequest request);

    record CreateRequest(
            String skillName,
            String baseVersionId,
            String description,
            String content,
            String visibleScope,
            Long ownerDeptId,
            List<TextFile> files) {}

    record TextFile(String path, String content) {}

    record UpdateFile(String fileId, String path, String content) {}

    record UpdateRequest(
            String revision,
            String description,
            String content,
            String visibleScope,
            Long ownerDeptId,
            List<SkillImportService.DependencyInput> dependencies,
            List<UpdateFile> files,
            String displayName) {
        public UpdateRequest(String revision, String description, String content, String visibleScope,
                Long ownerDeptId, List<SkillImportService.DependencyInput> dependencies, List<UpdateFile> files) {
            this(revision, description, content, visibleScope, ownerDeptId, dependencies, files, null);
        }
    }

    record PublishRequest(String revision, String platformVersion, List<String> acknowledgedRuleKeys) {}
}
