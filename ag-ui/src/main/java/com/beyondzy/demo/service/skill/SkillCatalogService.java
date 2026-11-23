package com.beyondzy.demo.service.skill;

/** 旧技能目录。写方法已停用，管理端改走 SkillAdminService。 */
public interface SkillCatalogService {
    SkillView saveMarkdown(byte[] body, boolean overwrite);

    SkillView saveArchive(byte[] zip, boolean overwrite);

    SkillView saveFiles(java.util.List<SkillUpload> files, boolean overwrite);

    SkillPage list(int page, int size);

    SkillView detail(String name, boolean includeBodies);

    void delete(String name);
}
