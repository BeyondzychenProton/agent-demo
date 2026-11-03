package com.beyondzy.demo.service;

import com.beyondzy.demo.domain.ThreadCatalogBean;
import java.util.List;

/** 会话目录的应用服务接口。 */
public interface ThreadCatalogService {
    ThreadCatalogBean findById(String id);
    List<ThreadCatalogBean> listRecent(int limit);
    List<ThreadCatalogBean> listAll();
    List<ThreadCatalogBean> listRecentAfter(int limit, Long updatedAt, String id);
    boolean create(ThreadCatalogBean thread);
    boolean update(ThreadCatalogBean thread);
    boolean deleteById(String id);
}
