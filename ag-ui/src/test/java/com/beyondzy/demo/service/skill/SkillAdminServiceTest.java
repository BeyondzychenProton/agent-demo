package com.beyondzy.demo.service.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beyondzy.demo.config.skill.SkillJdbc;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.mapper.skill.SkillMapper;
import io.agentscope.core.skill.AgentSkill;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/** 管理端保存后，只读仓库能读到带 frontmatter 的正文；详情只回纯正文。 */
class SkillAdminServiceTest {

    @Test
    void saveProjectsFrontmatterAndDetailReturnsBody() {
        try (SkillAdminHarness harness = SkillAdminHarness.open()) {
            harness.department(10, null, "研发");
            harness.user(1, 100L, 10, true);
            SkillDraft draft = new SkillDraft(
                    "meeting-booking",
                    "他说 \"你好\" 并且 C:\\temp\n下一行",
                    "正文",
                    "1",
                    null,
                    null,
                    List.of(new SkillDraft.SkillFile("references/faq.md", "问答")));
            harness.admin.create(1, draft);

            AgentSkill stored = harness.repository.getSkill("meeting-booking");
            assertTrue(stored.getSkillContent().startsWith("---\n"));
            assertTrue(stored.getSkillContent().contains("name: \"meeting-booking\""));
            assertTrue(stored.getSkillContent().contains("description: \"他说 \\\"你好\\\" 并且 C:\\\\temp 下一行\""));
            assertTrue(stored.getSkillContent().endsWith("正文"));
            assertEquals(SkillJdbc.SOURCE, stored.getSource());
            assertEquals("问答", stored.getResource("references/faq.md"));

            SkillAdminView.Detail detail = harness.admin.detail("meeting-booking");
            assertEquals("正文", detail.content());
            assertEquals("1", detail.visibleScope());
            assertEquals(10L, detail.ownerDeptId());
            assertEquals("研发", detail.ownerDeptName());
            assertEquals("references/faq.md", detail.files().get(0).path());
            assertFalse(detail.bound());

            SkillAdminView.Page page = harness.admin.list("meeting", "enabled", 1, 20);
            assertEquals(1, page.total());
            assertEquals("meeting-booking", page.items().get(0).name());
            assertTrue(harness.jdbc.queryForObject(
                    "SELECT skill_content FROM agentscope_skills WHERE name = 'meeting-booking'", String.class)
                    .contains("正文"));
        }
    }

    @Test
    void emptyQueryWithAllStatesPages() {
        try (SkillAdminHarness harness = SkillAdminHarness.open()) {
            harness.user(1, null, 0, true);
            harness.admin.create(1, draft("meeting-booking", List.of()));
            harness.admin.setEnabled("meeting-booking", false);
            harness.admin.create(1, new SkillDraft(
                    "weekly-report", "100%_done!", "正文", "0", null, "0", List.of()));

            // q= 空、state=all：MySQL 预编译若把 ESCAPE 反斜杠当成引号，绑 OFFSET 会 500。
            SkillAdminView.Page page = harness.admin.list("", "all", 1, 20);
            assertEquals(2, page.total());
            assertEquals(List.of("meeting-booking", "weekly-report"),
                    page.items().stream().map(SkillAdminView.Item::name).toList());
            assertEquals(0, harness.admin.list("", "all", 2, 20).items().size());
            assertEquals(1, harness.admin.list("100%_done!", "all", 1, 20).total());
            assertEquals(1, harness.admin.list("%", "enabled", 1, 20).total());
            assertEquals(1, harness.admin.list("_", "all", 1, 20).total());
            assertEquals(1, harness.admin.list("!", "all", 1, 20).total());
            assertMysqlPlaceholdersIntact("adminPage");
            assertMysqlPlaceholdersIntact("adminCount");
        }
    }

    @Test
    void illegalResourcePathFailsTheWholeSave() {
        try (SkillAdminHarness harness = SkillAdminHarness.open()) {
            harness.user(1, null, 0, true);
            SkillDraft bad = draft("meeting-booking", List.of(
                    new SkillDraft.SkillFile("references/ok.md", "好"),
                    new SkillDraft.SkillFile("../secret.md", "坏")));
            assertEquals(400, assertThrows(SkillException.class, () -> harness.admin.create(1, bad)).status());
            assertEquals(0, harness.admin.list(null, "all", 1, 20).total());

            harness.admin.create(1, draft("meeting-booking", List.of(new SkillDraft.SkillFile("references/ok.md", "好"))));
            SkillDraft worse = draft("ignored", List.of(new SkillDraft.SkillFile("SKILL.md", "不行")));
            assertEquals(400, assertThrows(SkillException.class,
                    () -> harness.admin.update(1, "meeting-booking", worse)).status());
            assertEquals("好", harness.repository.getSkill("meeting-booking").getResource("references/ok.md"));
        }
    }

    @Test
    void duplicateNameConflictsAndPutKeepsName() {
        try (SkillAdminHarness harness = SkillAdminHarness.open()) {
            harness.user(1, null, 0, true);
            harness.admin.create(1, draft("meeting-booking", List.of()));
            assertEquals(409, assertThrows(SkillException.class,
                    () -> harness.admin.create(1, draft("meeting-booking", List.of()))).status());

            harness.admin.update(1, "meeting-booking", new SkillDraft(
                    "renamed-skill", "改过的说明", "新正文", "2", 99L, "1",
                    List.of(new SkillDraft.SkillFile("scripts/echo.sh", "echo"))));
            AgentSkill stored = harness.repository.getSkill("meeting-booking");
            assertEquals("meeting-booking", stored.getName());
            assertEquals("改过的说明", stored.getDescription());
            assertEquals("echo", stored.getResource("scripts/echo.sh"));
            assertNull(stored.getResource("references/ok.md"));
            assertEquals(SkillJdbc.SOURCE, stored.getSource());
            assertEquals(1L, harness.jdbc.queryForObject(
                    "SELECT create_by FROM agentscope_skills WHERE name = 'meeting-booking'", Long.class));
            assertEquals(404, assertThrows(SkillException.class, () -> harness.admin.detail("renamed-skill")).status());
        }
    }

    @Test
    void importZipUsesDefaultsAndCannotSetSource() throws Exception {
        try (SkillAdminHarness harness = SkillAdminHarness.open()) {
            harness.user(1, null, 0, true);
            byte[] zip = zip(
                    "SKILL.md", """
                            ---
                            name: meeting-booking
                            description: 预约会议
                            ---
                            # 会议
                            """,
                    "references/guide.md", "指南");
            SkillAdminView.Detail imported = harness.admin.importDocument(1, "pack.zip", zip, null, null, null);
            assertEquals("meeting-booking", imported.name());
            assertEquals("0", imported.visibleScope());
            assertEquals("0", imported.status());
            assertEquals("# 会议\n", imported.content());
            assertEquals("指南", harness.repository.getSkill("meeting-booking").getResource("references/guide.md"));
            assertEquals(SkillJdbc.SOURCE, harness.repository.getSkill("meeting-booking").getSource());
        }
    }

    private static SkillDraft draft(String name, List<SkillDraft.SkillFile> files) {
        return new SkillDraft(name, "预约会议", "正文", "0", null, "0", files);
    }

    /**
     * H2 不按 MySQL 预编译规则处理反斜杠。这里用驱动同样的规则数问号：
     * 字符串内外的反斜杠都吃掉下一个字符，字符串里的问号不算占位符。
     */
    private static void assertMysqlPlaceholdersIntact(String method) {
        String sql = selectSql(method).replaceAll("#\\{[A-Za-z0-9_]+}", "?");
        int expected = count(selectSql(method), Pattern.compile("#\\{[A-Za-z0-9_]+}"));
        assertEquals(expected, mysqlPlaceholderCount(sql), method);
    }

    private static String selectSql(String method) {
        for (Method candidate : SkillMapper.class.getMethods()) {
            if (!candidate.getName().equals(method)) continue;
            Select select = candidate.getAnnotation(Select.class);
            if (select != null) return String.join(" ", select.value());
        }
        throw new IllegalStateException(method);
    }

    private static int mysqlPlaceholderCount(String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c != '\'' && c != '"') {
                if (c == '?') count++;
                continue;
            }
            char quote = c;
            for (i++; i < sql.length(); i++) {
                char inner = sql.charAt(i);
                if (inner == '\\') {
                    i++;
                    continue;
                }
                if (inner == quote) break;
            }
        }
        return count;
    }

    private static int count(String sql, Pattern pattern) {
        int count = 0;
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) count++;
        return count;
    }

    private static byte[] zip(String... nameAndBody) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < nameAndBody.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameAndBody[i]));
                zip.write(nameAndBody[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
