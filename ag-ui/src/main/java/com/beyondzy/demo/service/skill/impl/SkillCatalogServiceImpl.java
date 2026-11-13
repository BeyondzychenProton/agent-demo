package com.beyondzy.demo.service.skill.impl;

import com.beyondzy.demo.domain.skill.SkillBean;
import com.beyondzy.demo.exception.skill.SkillException;
import com.beyondzy.demo.service.skill.SkillCatalogService;
import com.beyondzy.demo.service.skill.SkillDocuments;
import com.beyondzy.demo.service.skill.SkillPage;
import com.beyondzy.demo.service.skill.SkillStore;
import com.beyondzy.demo.service.skill.SkillUpload;
import com.beyondzy.demo.service.skill.SkillView;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 技能目录：解析上传内容 → 落库（带归属）→ 按可见性返回。 */
@Service
public class SkillCatalogServiceImpl implements SkillCatalogService {

    private final SkillStore store;

    public SkillCatalogServiceImpl(SkillStore store) {
        this.store = store;
    }

    @Override
    public SkillView saveMarkdown(byte[] body, boolean overwrite) {
        return saveMarkdown(body, overwrite, SkillStore.PLATFORM_OWNER);
    }

    @Override
    public SkillView saveMarkdown(byte[] body, boolean overwrite, long ownerUserId) {
        return store(ownerUserId, SkillDocuments.markdown(body), overwrite);
    }

    @Override
    public SkillView saveArchive(byte[] zip, boolean overwrite) {
        return saveArchive(zip, overwrite, SkillStore.PLATFORM_OWNER);
    }

    @Override
    public SkillView saveArchive(byte[] zip, boolean overwrite, long ownerUserId) {
        return store(ownerUserId, SkillDocuments.archive(zip), overwrite);
    }

    @Override
    public SkillView saveFiles(List<SkillUpload> files, boolean overwrite) {
        return saveFiles(files, overwrite, SkillStore.PLATFORM_OWNER);
    }

    @Override
    public SkillView saveFiles(List<SkillUpload> files, boolean overwrite, long ownerUserId) {
        return store(ownerUserId, SkillDocuments.files(files), overwrite);
    }

    @Override
    public SkillPage list(int page, int size) {
        return list(SkillStore.PLATFORM_OWNER, page, size);
    }

    @Override
    public SkillPage list(long viewerUserId, int page, int size) {
        checkPage(page, size);
        long total = store.countVisible(viewerUserId);
        List<SkillPage.SkillSummary> items = store
                .pageVisible(viewerUserId, size, (long) (page - 1) * size)
                .stream()
                .map(SkillCatalogServiceImpl::summary)
                .toList();
        return new SkillPage(items, total, page, size);
    }

    @Override
    public SkillPage listAll(int page, int size) {
        checkPage(page, size);
        long total = store.countAll();
        List<SkillPage.SkillSummary> items = store
                .pageAll(size, (long) (page - 1) * size)
                .stream()
                .map(SkillCatalogServiceImpl::summary)
                .toList();
        return new SkillPage(items, total, page, size);
    }

    @Override
    public SkillView detail(String name, boolean includeBodies) {
        return detail(name, includeBodies, SkillStore.PLATFORM_OWNER);
    }

    @Override
    public SkillView detail(String name, boolean includeBodies, long viewerUserId) {
        return view(require(store.findVisible(viewerUserId, checkName(name))), includeBodies);
    }

    @Override
    public SkillView detailByOwner(String name, boolean includeBodies, long ownerUserId) {
        return view(require(store.findByOwner(ownerUserId, checkName(name))), includeBodies);
    }

    @Override
    public void delete(String name) {
        delete(name, SkillStore.PLATFORM_OWNER);
    }

    @Override
    public void delete(String name, long ownerUserId) {
        if (!store.delete(ownerUserId, checkName(name))) {
            throw new SkillException(404, "技能不存在: " + name);
        }
    }

    // ------------------------------------------------------------------

    private SkillView store(long ownerUserId, SkillDocuments.Parsed parsed, boolean overwrite) {
        if (ownerUserId < 0) {
            throw new SkillException(400, "归属用户不合法");
        }
        return view(store.save(ownerUserId, parsed, overwrite), false);
    }

    private static SkillStore.SkillRecord require(SkillStore.SkillRecord record) {
        if (record == null) {
            throw new SkillException(404, "技能不存在");
        }
        return record;
    }

    private SkillView view(SkillStore.SkillRecord record, boolean includeBodies) {
        List<String> paths = record.id() == null ? List.of() : store.resourcePaths(record.id());
        List<SkillView.SkillResource> files = new ArrayList<>();
        for (String path : paths) {
            String content = includeBodies ? record.resources().get(path) : null;
            files.add(new SkillView.SkillResource(path, SkillDocuments.kind(path), content));
        }
        return new SkillView(
                record.name(),
                record.description(),
                record.source(),
                record.ownerUserId(),
                record.createdAt(),
                record.updatedAt(),
                includeBodies ? record.skillContent() : null,
                List.copyOf(files));
    }

    private static SkillPage.SkillSummary summary(SkillBean row) {
        int count = row.getResourceCount() == null ? 0 : row.getResourceCount();
        long owner = row.getOwnerUserId() == null ? SkillStore.PLATFORM_OWNER : row.getOwnerUserId();
        return new SkillPage.SkillSummary(
                row.getName(),
                row.getDescription(),
                owner,
                row.getCreatedAt(),
                row.getUpdatedAt(),
                count);
    }

    private static void checkPage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new SkillException(400, "分页参数不合法");
        }
    }

    private static String checkName(String name) {
        if (name == null || !SkillDocuments.NAME.matcher(name).matches()) {
            throw new SkillException(400, "技能名不合法");
        }
        return name;
    }
}
