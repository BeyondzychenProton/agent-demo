package com.beyondzy.demo.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.beyondzy.demo.domain.ThreadCatalogBean;
import com.beyondzy.demo.mapper.ThreadCatalogMapper;
import com.beyondzy.demo.service.ThreadCatalogService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 会话目录服务实现，封装 MyBatis-Plus Mapper 操作。 */
@Service
public class ThreadCatalogServiceImpl implements ThreadCatalogService {
    private final ThreadCatalogMapper mapper;

    public ThreadCatalogServiceImpl(ThreadCatalogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ThreadCatalogBean findById(String id) {
        return mapper.selectById(id);
    }

    @Override
    public List<ThreadCatalogBean> listRecent(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return mapper.selectList(Wrappers.<ThreadCatalogBean>query()
                .orderByDesc("updated_at", "id")
                .last("LIMIT " + boundedLimit));
    }

    @Override
    public List<ThreadCatalogBean> listAll() {
        return mapper.selectList(Wrappers.emptyWrapper());
    }

    @Override
    public List<ThreadCatalogBean> listRecentAfter(int limit, Long updatedAt, String id) {
        int boundedLimit = Math.max(1, Math.min(limit, 101));
        QueryWrapper<ThreadCatalogBean> query = new QueryWrapper<>();
        if (updatedAt != null && id != null) {
            query.and(nested -> nested.lt("updated_at", updatedAt)
                    .or(equal -> equal.eq("updated_at", updatedAt).lt("id", id)));
        }
        return mapper.selectList(query.orderByDesc("updated_at", "id").last("LIMIT " + boundedLimit));
    }

    @Override
    @Transactional
    public boolean create(ThreadCatalogBean thread) {
        return mapper.insert(thread) == 1;
    }

    @Override
    @Transactional
    public boolean update(ThreadCatalogBean thread) {
        return mapper.updateById(thread) == 1;
    }

    @Override
    @Transactional
    public boolean deleteById(String id) {
        return mapper.deleteById(id) == 1;
    }
}
