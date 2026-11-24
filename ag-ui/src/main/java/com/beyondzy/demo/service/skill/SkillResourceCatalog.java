package com.beyondzy.demo.service.skill;

import java.util.List;

/**
 * 真实工具和平台能力目录。
 *
 * <p>只读发现结果，不执行业务工具。发现失败必须抛出，不能补一套模拟候选。
 */
public interface SkillResourceCatalog {
    SkillResourcePage page(String kind, String query, int page, int size);

    /** includeMcp 为假时只返回本进程注册的平台能力，不访问外部发现。 */
    CatalogView capture(boolean includeMcp);

    record SkillResourceOption(
            String targetKind,
            String providerKey,
            String toolKey,
            String displayName,
            List<String> capabilityKeys,
            boolean available) {}

    record SkillResourcePage(List<SkillResourceOption> items, long total, int page, int size) {}

    record CatalogView(List<SkillResourceOption> options) {
        public String fingerprint() {
            return options.stream()
                    .map(option -> option.targetKind() + "|" + option.providerKey() + "|" + option.toolKey()
                            + "|" + option.available())
                    .sorted()
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
        }
    }
}
