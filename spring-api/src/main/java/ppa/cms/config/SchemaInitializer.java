package ppa.cms.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class SchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final CmsProperties properties;

    public SchemaInitializer(JdbcTemplate jdbc, CmsProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!properties.isSchemaInit()) {
            return;
        }
        if (tableExists()) {
            addIpAddressColumn();
            ensureRemoteDeskAuth();
            ensureUserRoles();
            widenUserRoleColumn();
            return;
        }
        String script = new String(new ClassPathResource("schema.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : script.split(";")) {
            String sql = Arrays.stream(statement.split("\\R")).filter(line -> !line.trim().startsWith("--")).reduce("", (left, right) -> left + "\n" + right).trim();
            if (!sql.isEmpty()) {
                jdbc.execute(sql);
            }
        }
    }

    private void addIpAddressColumn() {
        Integer tables = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = database() and table_name = 'cms_audit_events'",
                Integer.class
        );
        if (tables == null || tables == 0) {
            return;
        }
        Integer columns = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema = database() and table_name = 'cms_audit_events' and column_name = 'ip_address'",
                Integer.class
        );
        if (columns != null && columns == 0) {
            jdbc.execute("alter table cms_audit_events add column ip_address varchar(45) null after actor_username");
        }
    }

    private void ensureRemoteDeskAuth() {
        addColumnIfMissing("users", "remote_pin", "alter table users add column remote_pin varchar(255) null");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS remote_desk_credentials (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                    user_id BIGINT UNSIGNED NOT NULL,
                    credential_id VARBINARY(1023) NOT NULL,
                    public_key BLOB NOT NULL,
                    algorithm VARCHAR(16) NOT NULL,
                    sign_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NULL,
                    PRIMARY KEY (id),
                    KEY remote_desk_credentials_user_id_index (user_id),
                    CONSTRAINT remote_desk_credentials_user_id_foreign FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS remote_desk_challenges (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                    user_id BIGINT UNSIGNED NOT NULL,
                    purpose VARCHAR(20) NOT NULL,
                    challenge VARBINARY(64) NOT NULL,
                    expires_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (id),
                    KEY remote_desk_challenges_user_id_index (user_id),
                    CONSTRAINT remote_desk_challenges_user_id_foreign FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS remote_desk_grants (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                    user_id BIGINT UNSIGNED NOT NULL,
                    system_id BIGINT UNSIGNED NOT NULL,
                    desk_type VARCHAR(20) NOT NULL,
                    token_hash CHAR(64) NOT NULL,
                    expires_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY remote_desk_grants_token_hash_unique (token_hash),
                    KEY remote_desk_grants_user_id_index (user_id),
                    CONSTRAINT remote_desk_grants_user_id_foreign FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    private void ensureUserRoles() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS data_permissions (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                    code VARCHAR(80) NOT NULL,
                    module VARCHAR(40) NOT NULL,
                    module_label VARCHAR(80) NOT NULL,
                    label VARCHAR(120) NOT NULL,
                    description VARCHAR(255) NULL,
                    sort_order INT NOT NULL DEFAULT 0,
                    PRIMARY KEY (id),
                    UNIQUE KEY data_permissions_code_unique (code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_roles (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                    name VARCHAR(80) NOT NULL,
                    description VARCHAR(255) NULL,
                    code VARCHAR(40) NULL,
                    built_in TINYINT(1) NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NULL,
                    updated_at TIMESTAMP NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY user_roles_name_unique (name),
                    UNIQUE KEY user_roles_code_unique (code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_role_permissions (
                    user_role_id BIGINT UNSIGNED NOT NULL,
                    data_permission_id BIGINT UNSIGNED NOT NULL,
                    PRIMARY KEY (user_role_id, data_permission_id),
                    KEY user_role_permissions_permission_index (data_permission_id),
                    CONSTRAINT user_role_permissions_role_foreign FOREIGN KEY (user_role_id) REFERENCES user_roles (id) ON DELETE CASCADE,
                    CONSTRAINT user_role_permissions_permission_foreign FOREIGN KEY (data_permission_id) REFERENCES data_permissions (id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    private void widenUserRoleColumn() {
        Integer length = jdbc.queryForObject(
                "select character_maximum_length from information_schema.columns where table_schema = database() and table_name = 'users' and column_name = 'role'",
                Integer.class
        );
        if (length != null && length < 40) {
            jdbc.execute("alter table users modify column role varchar(40) not null default 'user'");
        }
    }

    private void addColumnIfMissing(String table, String column, String alter) {
        Integer columns = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema = database() and table_name = ? and column_name = ?",
                Integer.class,
                table,
                column
        );
        if (columns != null && columns == 0) {
            jdbc.execute(alter);
        }
    }

    private boolean tableExists() {
        try {
            jdbc.queryForObject("select count(*) from users", Long.class);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
