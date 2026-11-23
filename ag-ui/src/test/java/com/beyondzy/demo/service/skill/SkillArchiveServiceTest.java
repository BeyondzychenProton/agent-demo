package com.beyondzy.demo.service.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beyondzy.demo.exception.skill.SkillException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** zip 与相对路径的解析规则。入库改由管理端导入测试覆盖。 */
class SkillArchiveServiceTest {
    private static final String MARKDOWN = """
            ---
            name: meeting-booking
            description: 预约会议
            ---
            # 会议
            """;

    @Test
    void zipWithWrapperBecomesOneSkillAndResources() {
        SkillDocuments.Parsed parsed = SkillDocuments.archive(zip(
                "meeting-booking/SKILL.md", MARKDOWN,
                "meeting-booking/references/guide.md", "指南",
                "meeting-booking/scripts/echo.sh", "echo hi"));
        assertEquals("meeting-booking", parsed.name());
        assertEquals(MARKDOWN, parsed.skillContent());
        assertEquals("指南", parsed.resources().get("references/guide.md"));
        assertEquals("echo hi", parsed.resources().get("scripts/echo.sh"));
    }

    @Test
    void flatZipAndRelativeMultipartUseTheSameRules() {
        SkillDocuments.Parsed flat = SkillDocuments.archive(zip(
                "SKILL.md", MARKDOWN,
                "references/guide.md", "指南"));
        assertEquals("指南", flat.resources().get("references/guide.md"));
        SkillDocuments.Parsed files = SkillDocuments.files(List.of(
                new SkillUpload("meeting-booking/SKILL.md", MARKDOWN.getBytes(StandardCharsets.UTF_8)),
                new SkillUpload("meeting-booking/references/guide.md", "另一份".getBytes(StandardCharsets.UTF_8)),
                new SkillUpload("meeting-booking/scripts/echo.sh", "echo".getBytes(StandardCharsets.UTF_8))));
        assertEquals("另一份", files.resources().get("references/guide.md"));
        assertEquals("echo", files.resources().get("scripts/echo.sh"));
    }

    @Test
    void traversalBinaryAndDisallowedDirectoriesAreRejected() {
        assertEquals(400, status(zip(
                "meeting-booking/SKILL.md", MARKDOWN,
                "meeting-booking/../outside.md", "no")));
        assertEquals(400, status(zip(
                "meeting-booking/SKILL.md", MARKDOWN,
                "meeting-booking/scripts/bin.sh", new byte[] {(byte) 0xff})));
        assertEquals(400, status(zip(
                "meeting-booking/SKILL.md", MARKDOWN,
                "meeting-booking/reference/guide.md", "no")));
        assertEquals(400, status(zip(
                "meeting-booking/SKILL.md", MARKDOWN,
                "meeting-booking/examples/guide.md", "no")));
        assertEquals(400, status(zip("C:/SKILL.md", MARKDOWN)));
        assertEquals(400, status(zip("/tmp/SKILL.md", MARKDOWN)));
        assertEquals(400, assertThrows(SkillException.class, () -> SkillDocuments.files(List.of(
                new SkillUpload("../SKILL.md", MARKDOWN.getBytes(StandardCharsets.UTF_8))))).status());
    }

    private static int status(byte[] zip) {
        return assertThrows(SkillException.class, () -> SkillDocuments.archive(zip)).status();
    }

    private static byte[] zip(Object... nameAndBody) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (int i = 0; i < nameAndBody.length; i += 2) {
                    zip.putNextEntry(new ZipEntry((String) nameAndBody[i]));
                    Object body = nameAndBody[i + 1];
                    byte[] bytes = body instanceof byte[] raw ? raw : ((String) body).getBytes(StandardCharsets.UTF_8);
                    zip.write(bytes);
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
