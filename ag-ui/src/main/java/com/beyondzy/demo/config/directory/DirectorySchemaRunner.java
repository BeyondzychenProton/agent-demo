package com.beyondzy.demo.config.directory;

import com.beyondzy.demo.service.user.UserService;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/** 启动时初始化通讯录表和用户扩展字段，并为历史同步账号补齐首次改密标记。 */
@Component
@Order(2)
public class DirectorySchemaRunner implements ApplicationRunner {
    private final DataSource dataSource;
    private final UserService users;

    public DirectorySchemaRunner(DataSource dataSource, UserService users) {
        this.dataSource = dataSource;
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        executeSchema("V7__app_directory.sql");
        ensureUserColumn("mantianxing_user_id", "V8__app_user_mantianxing.sql");
        ensureUserColumn("must_change_password", "V9__app_user_password_change.sql");
        users.initializeDirectoryPasswordChanges();
    }

    /** 已存在的字段跳过迁移，确保本地服务重启时可重复执行。 */
    private void ensureUserColumn(String column, String script) throws SQLException {
        try (var connection = dataSource.getConnection();
             var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "app_user", column)) {
            while (columns.next()) {
                if ("app_user".equals(columns.getString("TABLE_NAME"))
                        && column.equals(columns.getString("COLUMN_NAME"))) return;
            }
        }
        executeSchema(script);
    }

    private void executeSchema(String script) {
        var populator = new ResourceDatabasePopulator(new ClassPathResource("db/mysql/" + script));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
    }
}
