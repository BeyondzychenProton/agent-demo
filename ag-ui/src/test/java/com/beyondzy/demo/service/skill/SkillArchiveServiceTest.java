package com.beyondzy.demo.service.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.service.skill.SkillTestSupport.Fixture;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** zip 与相对路径上传：附属文件落资源表，穿越和二进制不入库。 */
class SkillArchiveServiceTest {
    private static final String MARKDOWN = """
            ---
            name: meeting-booking
            description: 预约会议
            ---
            # 会议
            """;

    @Test
    void zipWithWrapperBecomesOneSkillAndResourceRows() {
        try (Fixture fixture = Fixture.open("archive")) {
            fixture.service.saveArchive(zip(
                    "meeting-booking/SKILL.md", MARKDOWN,
                    "meeting-booking/references/guide.md", "指南",
                    "meeting-booking/scripts/echo.sh", "echo hi"), false);

            SkillStore.SkillRecord stored =
                    fixture.store.findByOwner(SkillStore.PLATFORM_OWNER, "meeting-booking");
            assertEquals(MARKDOWN, stored.skillContent());
            assertEquals("指南", stored.resources().get("references/guide.md"));
            assertEquals("echo hi", stored.resources().get("scripts/echo.sh"));
            assertEquals(2, fixture.service.list(1, 20).items().get(0).resourceCount());
            assertEquals(List.of("references/guide.md", "scripts/echo.sh"),
                    fixture.service.detail("meeting-booking", false).resources().stream()
                            .map(SkillView.SkillResource::path).toList());
        }
    }

    @Test
    void flatZipAndRelativeMultipartUseTheSameRules() {
        try (Fixture fixture = Fixture.open("archive-flat")) {
            fixture.service.saveArchive(zip(
                    "SKILL.md", MARKDOWN,
                    "references/guide.md", "指南"), false);
            assertEquals("指南",
                    fixture.store.findByOwner(SkillStore.PLATFORM_OWNER, "meeting-booking")
                            .resources().get("references/guide.md"));
            fixture.service.delete("meeting-booking");

            fixture.service.saveFiles(List.of(
                    new SkillUpload("meeting-booking/SKILL.md", MARKDOWN.getBytes(StandardCharsets.UTF_8)),
                    new SkillUpload("meeting-booking/references/guide.md", "另一份".getBytes(StandardCharsets.UTF_8)),
                    new SkillUpload("meeting-booking/scripts/echo.sh", "echo".getBytes(StandardCharsets.UTF_8))), false);
            SkillStore.SkillRecord stored =
                    fixture.store.findByOwner(SkillStore.PLATFORM_OWNER, "meeting-booking");
            assertEquals("另一份", stored.resources().get("references/guide.md"));
            assertEquals("echo", stored.resources().get("scripts/echo.sh"));
        }
    }

    @Test
    void traversalBinaryAndDisallowedDirectoriesAreNotStored() {
        try (Fixture fixture = Fixture.open("archive-invalid")) {
            assertEquals(400, status(fixture, zip(
                    "meeting-booking/SKILL.md", MARKDOWN,
                    "meeting-booking/../outside.md", "no")));
            assertEquals(400, status(fixture, zip(
                    "meeting-booking/SKILL.md", MARKDOWN,
                    "meeting-booking/scripts/bin.sh", new byte[] {(byte) 0xff})));
            assertEquals(400, status(fixture, zip(
                    "meeting-booking/SKILL.md", MARKDOWN,
                    "meeting-booking/reference/guide.md", "no")));
            assertEquals(400, status(fixture, zip(
                    "meeting-booking/SKILL.md", MARKDOWN,
                    "meeting-booking/examples/guide.md", "no")));
            assertEquals(400, status(fixture, zip("C:/SKILL.md", MARKDOWN)));
            assertEquals(400, status(fixture, zip("/tmp/SKILL.md", MARKDOWN)));
            assertEquals(400, status(fixture.service, List.of(
                    new SkillUpload("../SKILL.md", MARKDOWN.getBytes(StandardCharsets.UTF_8)))));
            assertEquals(0, fixture.service.list(1, 20).total());
        }
    }

    @Test
    void overwriteReplacesResources() {
        try (Fixture fixture = Fixture.open("archive-overwrite")) {
            fixture.service.saveArchive(zip(
                    "SKILL.md", MARKDOWN,
                    "scripts/echo.sh", "echo hi"), false);
            fixture.service.saveArchive(zip(
                    "SKILL.md", MARKDOWN,
                    "references/guide.md", "指南"), true);
            SkillStore.SkillRecord stored =
                    fixture.store.findByOwner(SkillStore.PLATFORM_OWNER, "meeting-booking");
            assertEquals("指南", stored.resources().get("references/guide.md"));
            assertNull(stored.resources().get("scripts/echo.sh"));
            assertEquals(1, fixture.service.list(1, 20).items().get(0).resourceCount());
        }
    }

    private static int status(Fixture fixture, byte[] zip) {
        return assertThrows(SkillException.class, () -> fixture.service.saveArchive(zip, false)).status();
    }

    private static int status(SkillCatalogService service, List<SkillUpload> files) {
        return assertThrows(SkillException.class, () -> service.saveFiles(files, false)).status();
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
