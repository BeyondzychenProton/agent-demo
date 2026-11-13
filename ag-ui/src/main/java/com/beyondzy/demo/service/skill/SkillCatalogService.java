package com.beyondzy.demo.service.skill;

import java.util.List;

/**
 * 技能目录的读写。
 *
 * <p>作用域语义：
 * <ul>
 *   <li>{@code ownerUserId = 0} → 平台公共技能，所有人可见；</li>
 *   <li>{@code ownerUserId = N} → 仅用户 N 可见；</li>
 *   <li>不带 owner 参数的重载等价于平台作用域（0），供管理台/兼容调用使用。</li>
 * </ul>
 * 上传/删除的权限由 Web 层（管理员）保证，服务层只负责落库与可见性。
 */
public interface SkillCatalogService {

    SkillView saveMarkdown(byte[] body, boolean overwrite);

    SkillView saveMarkdown(byte[] body, boolean overwrite, long ownerUserId);

    SkillView saveArchive(byte[] zip, boolean overwrite);

    SkillView saveArchive(byte[] zip, boolean overwrite, long ownerUserId);

    SkillView saveFiles(List<SkillUpload> files, boolean overwrite);

    SkillView saveFiles(List<SkillUpload> files, boolean overwrite, long ownerUserId);

    /** 平台技能分页（兼容旧调用）。 */
    SkillPage list(int page, int size);

    /** 某用户可见的技能分页（平台 + 本人）。 */
    SkillPage list(long viewerUserId, int page, int size);

    /** 全量分页（管理员）。 */
    SkillPage listAll(int page, int size);

    SkillView detail(String name, boolean includeBodies);

    SkillView detail(String name, boolean includeBodies, long viewerUserId);

    /** 按归属取详情（管理员，或本人查看自己的私有技能）。 */
    SkillView detailByOwner(String name, boolean includeBodies, long ownerUserId);

    void delete(String name);

    void delete(String name, long ownerUserId);
}
