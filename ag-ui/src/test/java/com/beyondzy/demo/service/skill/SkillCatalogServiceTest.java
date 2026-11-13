package com.beyondzy.demo.service.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import com.beyondzy.demo.service.skill.SkillTestSupport.Fixture;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** 单个 SKILL.md 的保存、列表、详情和删除。列表查询不读取正文。 */
class SkillCatalogServiceTest {
    private static final String MARKDOWN = """
            ---
            name: meeting-booking
            description: 预约会议
            license: MIT
            ---
            # 会议
            """;

    @Test
    void savesListDetailAndDeleteWithoutLoadingBodiesOnList() {
        try (Fixture fixture = Fixture.open("catalog")) {
            SkillCatalogService service = fixture.service;
            service.saveMarkdown(MARKDOWN.getBytes(StandardCharsets.UTF_8), false);

            SkillStore.SkillRecord stored = fixture.store.findByOwner(SkillStore.PLATFORM_OWNER, "meeting-booking");
            assertEquals(MARKDOWN, stored.skillContent());
            assertEquals(SkillStore.SOURCE_PLATFORM, stored.source());
            assertEquals(SkillStore.PLATFORM_OWNER, stored.ownerUserId());
            assertEquals("MIT", String.valueOf(stored.metadata().get("license")));
            assertEquals("预约会议", stored.description());

            SkillPage page = service.list(1, 20);
            assertEquals(1, page.total());
            assertEquals("meeting-booking", page.items().get(0).name());
            assertEquals("预约会议", page.items().get(0).description());
            assertEquals(0, page.items().get(0).resourceCount());
            assertEquals(0L, page.items().get(0).ownerUserId());
            assertNotNull(page.items().get(0).createdAt());
            assertNotNull(page.items().get(0).updatedAt());

            SkillView hidden = service.detail("meeting-booking", false);
            assertNull(hidden.skillContent());
            assertTrue(hidden.resources().isEmpty());
            assertEquals(MARKDOWN, service.detail("meeting-booking", true).skillContent());

            service.delete("meeting-booking");
            SkillException missing = assertThrows(SkillException.class,
                    () -> service.detail("meeting-booking", false));
            assertEquals(404, missing.status());
        }
    }

    @Test
    void duplicateWithoutOverwriteIsConflictAndOverwriteReplaces() {
        try (Fixture fixture = Fixture.open("catalog-dup")) {
            SkillCatalogService service = fixture.service;
            byte[] first = MARKDOWN.getBytes(StandardCharsets.UTF_8);
            service.saveMarkdown(first, false);
            SkillException conflict = assertThrows(SkillException.class, () -> service.saveMarkdown(first, false));
            assertEquals(409, conflict.status());

            String replaced = MARKDOWN.replace("预约会议", "改过的说明");
            service.saveMarkdown(replaced.getBytes(StandardCharsets.UTF_8), true);
            SkillStore.SkillRecord stored =
                    fixture.store.findByOwner(SkillStore.PLATFORM_OWNER, "meeting-booking");
            assertEquals("改过的说明", stored.description());
            assertEquals(replaced, stored.skillContent());
        }
    }

    @Test
    void rejectsInvalidNameOversizedAndBinary() {
        try (Fixture fixture = Fixture.open("catalog-invalid")) {
            SkillCatalogService service = fixture.service;
            assertEquals(400, status(service, """
                    ---
                    name: Meeting
                    description: 大写不行
                    ---
                    # x
                    """.getBytes(StandardCharsets.UTF_8)));
            assertEquals(400, status(service, """
                    # 没有 frontmatter
                    """.getBytes(StandardCharsets.UTF_8)));

            byte[] oversized = new byte[(256 * 1024) + 1];
            Arrays.fill(oversized, (byte) 'a');
            assertEquals(400, status(service, oversized));
            assertEquals(400, status(service, new byte[] {(byte) 0xff, (byte) 0xfe}));
            assertEquals(400, status(service, "---\nname: ok\0\ndescription: x\n---\n".getBytes(StandardCharsets.UTF_8)));
            assertEquals(0, service.list(1, 20).total());
        }
    }

    @Test
    void listDoesNotSelectSkillContent() {
        // 列表 SQL 只查汇总列：正文（LONGTEXT）不应随分页返回
        try (Fixture fixture = Fixture.open("catalog-sql")) {
            fixture.service.saveMarkdown(MARKDOWN.getBytes(StandardCharsets.UTF_8), false);
            SkillPage page = fixture.service.list(1, 20);
            assertEquals(1, page.total());
            assertFalse(page.items().isEmpty());
            // listAll（管理员视角）同样不读正文
            assertEquals(1, fixture.service.listAll(1, 20).total());
        }
    }

    private static int status(SkillCatalogService service, byte[] body) {
        return assertThrows(SkillException.class, () -> service.saveMarkdown(body, false)).status();
    }
}
