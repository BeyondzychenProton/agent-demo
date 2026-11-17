package com.beyondzy.demo.web.directory;

import com.beyondzy.demo.handler.admin.AdminAccess;
import com.beyondzy.demo.service.user.UserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DirectoryStore implements ApplicationRunner {
    private static final String DEPARTMENT_UPSERT = """
            INSERT INTO app_directory_department
              (source_id, source_version, parent_id, name, sort_order, status, is_deleted)
            VALUES (?, ?, ?, ?, ?, ?, ?) AS incoming
            ON DUPLICATE KEY UPDATE
              parent_id = IF(incoming.source_version > app_directory_department.source_version, incoming.parent_id, app_directory_department.parent_id),
              name = IF(incoming.source_version > app_directory_department.source_version, incoming.name, app_directory_department.name),
              sort_order = IF(incoming.source_version > app_directory_department.source_version, incoming.sort_order, app_directory_department.sort_order),
              status = IF(incoming.source_version > app_directory_department.source_version, incoming.status, app_directory_department.status),
              is_deleted = IF(incoming.source_version > app_directory_department.source_version, incoming.is_deleted, app_directory_department.is_deleted),
              source_version = GREATEST(incoming.source_version, app_directory_department.source_version)
            """;

    private static final String USER_UPSERT = """
            INSERT INTO app_directory_user
              (source_id, source_version, department_id, account, name, email, status, is_deleted)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) AS incoming
            ON DUPLICATE KEY UPDATE
              department_id = IF(incoming.source_version > app_directory_user.source_version, incoming.department_id, app_directory_user.department_id),
              account = IF(incoming.source_version > app_directory_user.source_version, incoming.account, app_directory_user.account),
              name = IF(incoming.source_version > app_directory_user.source_version, incoming.name, app_directory_user.name),
              email = IF(incoming.source_version > app_directory_user.source_version, incoming.email, app_directory_user.email),
              status = IF(incoming.source_version > app_directory_user.source_version, incoming.status, app_directory_user.status),
              is_deleted = IF(incoming.source_version > app_directory_user.source_version, incoming.is_deleted, app_directory_user.is_deleted),
              source_version = GREATEST(incoming.source_version, app_directory_user.source_version)
            """;

    private static final ObjectMapper HISTORY_JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final AdminAccess admin;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();


    public DirectoryStore(JdbcTemplate jdbc, DataSource dataSource, AdminAccess admin) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.admin = admin;
    }

    @Override
    public void run(ApplicationArguments args) {
        executeSchema("V7__app_directory.sql");
        ensureUserColumn("mantianxing_user_id", "V8__app_user_mantianxing.sql");
        ensureUserColumn("must_change_password", "V9__app_user_password_change.sql");
        initializePasswordChanges();
    }

    private void ensureUserColumn(String column, String script) {
        Integer applied = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'app_user' AND column_name = ?
                """, Integer.class, column);
        if (Integer.valueOf(0).equals(applied)) {
            executeSchema(script);
        }
    }

    private void initializePasswordChanges() {
        var pending = jdbc.query("""
                SELECT id, password_hash FROM app_user
                WHERE mantianxing_user_id IS NOT NULL AND must_change_password IS NULL
                """, (rs, n) -> new ExistingAccount(rs.getLong(1), rs.getString(2)));
        for (var account : pending) {
            boolean defaultPassword = passwordEncoder.matches(UserService.SYNC_INITIAL_PASSWORD, account.passwordHash());
            jdbc.update("""
                    UPDATE app_user SET must_change_password = ?
                    WHERE id = ? AND must_change_password IS NULL AND password_hash = ?
                    """, defaultPassword ? 1 : 0, account.id(), account.passwordHash());
        }
    }

    private record ExistingAccount(long id, String passwordHash) {}

    private void executeSchema(String script) {
        var populator = new ResourceDatabasePopulator(new ClassPathResource("db/mysql/" + script));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
    }

    @Transactional
    public void apply(DirectoryPush push) {
        // Acquire row locks in the same order across batches. Update source_version last in each upsert.
        for (var row : push.departments().stream().sorted(Comparator.comparing(DirectoryPush.Department::deptId)).toList()) {
            boolean deleted = row.deleted();
            jdbc.update(DEPARTMENT_UPSERT, row.deptId(), row.version(), deleted ? null : optionalId(row.parentId()),
                    deleted ? null : row.deptName(), deleted || row.orderNum() == null ? 0 : row.orderNum(),
                    deleted ? null : row.status(), deleted);
        }
        for (var row : push.users().stream().sorted(Comparator.comparing(DirectoryPush.Member::userId)).toList()) {
            boolean deleted = row.deleted();
            // Apply login changes only for the version that advances this member.
            var currentVersions = jdbc.query("""
                    SELECT source_version FROM app_directory_user WHERE source_id = ? FOR UPDATE
                    """, (rs, n) -> rs.getLong(1), row.userId());
            if (!currentVersions.isEmpty() && currentVersions.getFirst() >= row.version()) continue;
            syncLoginAccount(row);
            jdbc.update(USER_UPSERT, row.userId(), row.version(), deleted ? null : optionalId(row.deptId()),
                    deleted ? null : row.userName(), deleted ? null : row.nickName(),
                    deleted ? null : row.email(), deleted ? null : row.status(), deleted);
        }
        recordPush(push, PushStatus.ACCEPTED);
    }

    private void syncLoginAccount(DirectoryPush.Member row) {
        boolean inactive = row.deleted() || "1".equals(row.status());
        if (row.deleted()) {
            jdbc.update("""
                    UPDATE app_user SET is_deleted = 1, gmt_modified = NOW(3)
                    WHERE mantianxing_user_id = ?
                    """, row.userId());
        } else {
            String phone = row.phonenumber().strip();
            try {
                var accounts = jdbc.query("SELECT id, phone FROM app_user WHERE mantianxing_user_id = ?",
                        (rs, n) -> new LoginAccount(rs.getLong(1), rs.getString(2)), row.userId());
                var account = accounts.isEmpty() ? null : accounts.getFirst();
                if (admin.isAdmin(phone) && (account == null || !phone.equals(account.phone()))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone reserved for the local admin");
                }
                if (account == null) {
                    jdbc.update("""
                            INSERT INTO app_user (mantianxing_user_id, name, phone, password_hash,
                                                  is_deleted, must_change_password, gmt_create, gmt_modified)
                            VALUES (?, ?, ?, ?, ?, 1, NOW(3), NOW(3))
                            """, row.userId(), row.nickName(), phone, passwordEncoder.encode(UserService.SYNC_INITIAL_PASSWORD), inactive);
                } else {
                    jdbc.update("""
                            UPDATE app_user SET name = ?, phone = ?, is_deleted = ?, gmt_modified = NOW(3)
                            WHERE id = ?
                            """, row.nickName(), phone, inactive, account.id());
                }
            } catch (DuplicateKeyException ex) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone already belongs to another account", ex);
            }
        }
        if (inactive) {
            jdbc.update("""
                    DELETE FROM auth_session WHERE user_id =
                      (SELECT id FROM app_user WHERE mantianxing_user_id = ?)
                    """, row.userId());
        }
    }

    private record LoginAccount(long id, String phone) {}

    public void recordPush(DirectoryPush push, PushStatus status) {
        jdbc.update("""
                INSERT INTO app_directory_push_history (source, status, department_count, user_count, payload)
                VALUES ('MANTIANXING', ?, ?, ?, ?)
                """, status.name(), push == null ? 0 : push.departments().size(),
                push == null ? 0 : push.users().size(), push == null ? null : historyPayload(push));
    }

    private static String historyPayload(DirectoryPush push) {
        try {
            return HISTORY_JSON.writeValueAsString(push);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize directory push", ex);
        }
    }

    private static Long optionalId(Long id) {
        return id == null || id == 0 ? null : id;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Directory read() {
        var departments = jdbc.query("""
                SELECT source_id, parent_id, name, status FROM app_directory_department
                WHERE is_deleted = FALSE ORDER BY sort_order, source_id
                """, (rs, n) -> new Department(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        var users = jdbc.query("""
                SELECT source_id, department_id, account, name, email, status FROM app_directory_user
                WHERE is_deleted = FALSE ORDER BY name, source_id
                """, (rs, n) -> new Member(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)));
        return new Directory(departments, users);
    }

    public enum PushStatus { ACCEPTED, REJECTED, FAILED }

    public record Directory(List<Department> departments, List<Member> users) {}
    public record Department(String id, String parentId, String name, String status) {}
    public record Member(String id, String departmentId, String account, String name, String email, String status) {}
}
