package com.beyondzy.demo.service.directory;

import com.beyondzy.demo.contract.directory.DirectoryPush;
import java.util.List;

/** 处理组织通讯录推送及管理端查询。 */
public interface DirectoryService {
    /** 同一事务内提交通讯录、登录账号和成功历史。 */
    void apply(DirectoryPush push);
    /** 记录单次推送结果。请求体无效时只记录结果。 */
    void recordPush(DirectoryPush push, PushStatus status);
    /** 读取同一数据库快照中的部门和成员。 */
    Directory read();

    enum PushStatus { ACCEPTED, REJECTED, FAILED }

    record Directory(List<Department> departments, List<Member> users) {}
    record Department(String id, String parentId, String name, String status) {}
    record Member(String id, String departmentId, String account, String name, String email, String status) {}
}
