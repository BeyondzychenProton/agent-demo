package com.beyondzy.demo.contract.directory;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** 满天星推送批次。反序列化时校验整批，删除记录仅校验来源主键和版本。 */
public record DirectoryPush(List<Department> departments, List<Member> users) {
    public DirectoryPush {
        departments = departments == null ? List.of() : departments;
        users = users == null ? List.of() : users;
        int size = departments.size() + users.size();
        require(size > 0 && size <= 500, "Expected 1 to 500 records");
        for (Department row : departments) {
            require(row != null, "Null department");
            validateRow(row.deptId(), row.version(), row.delFlag());
            if (!row.deleted()) {
                require((row.parentId() == null || row.parentId() >= 0)
                        && !row.deptId().equals(row.parentId()), "Invalid parentId");
                require(validText(row.deptName(), 30) && validStatus(row.status()), "Invalid department");
            }
        }
        for (Member row : users) {
            require(row != null, "Null user");
            validateRow(row.userId(), row.version(), row.delFlag());
            if (!row.deleted()) {
                require(row.deptId() == null || row.deptId() >= 0, "Invalid deptId");
                require(validText(row.userName(), 50) && validText(row.nickName(), 50)
                        && (row.email() == null || length(row.email()) <= 50)
                        && validStatus(row.status()), "Invalid user");
                require(row.phonenumber() != null && row.phonenumber().strip().matches("^\\+?[0-9]{7,20}$"),
                        "Invalid phonenumber");
            }
        }
    }

    /** 部门变更，保留来源字段名及状态值。 */
    public record Department(Long deptId, Long version, String delFlag, Long parentId,
                      String deptName, Integer orderNum, String status) {
        public boolean deleted() { return "2".equals(delFlag); }
    }

    /** 用户变更。手机号只接收，序列化推送历史时不写出。 */
    public record Member(Long userId, Long version, String delFlag, Long deptId,
                  String userName, String nickName, String email, String status,
                  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String phonenumber) {
        public boolean deleted() { return "2".equals(delFlag); }
    }

    private static void validateRow(Long id, Long version, String delFlag) {
        require(id != null && id > 0 && version != null && version > 0, "Invalid ID or version");
        require("0".equals(delFlag) || "2".equals(delFlag), "Invalid delFlag");
    }

    private static boolean validText(String text, int maxLength) {
        return text != null && !text.isBlank() && length(text) <= maxLength;
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static boolean validStatus(String status) {
        return "0".equals(status) || "1".equals(status);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
