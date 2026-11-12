package com.beyondzy.demo.service.skill;

/** 技能目录的读写。写入只经 JDBC 技能库，列表不加载正文。 */
public interface SkillCatalogService {
    SkillView saveMarkdown(byte[] body, boolean overwrite);

    SkillView saveArchive(byte[] zip, boolean overwrite);

    SkillView saveFiles(java.util.List<SkillUpload> files, boolean overwrite);

    SkillPage list(int page, int size);

    SkillView detail(String name, boolean includeBodies);

    void delete(String name);
}
