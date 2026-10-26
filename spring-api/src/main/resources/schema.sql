CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    first_name VARCHAR(50) NULL,
    middle_name VARCHAR(50) NULL,
    last_name VARCHAR(50) NULL,
    suffix VARCHAR(10) NULL,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'user',
    email_verified_at TIMESTAMP NULL,
    password VARCHAR(255) NOT NULL,
    remember_token VARCHAR(100) NULL,
    disabled_at TIMESTAMP NULL,
    created_at TIMESTAMP NULL,
    updated_at TIMESTAMP NULL,
    PRIMARY KEY (id),
    UNIQUE KEY users_username_unique (username),
    UNIQUE KEY users_email_unique (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    email VARCHAR(255) NOT NULL,
    token VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NULL,
    PRIMARY KEY (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sessions (
    id VARCHAR(255) NOT NULL,
    user_id BIGINT UNSIGNED NULL,
    ip_address VARCHAR(45) NULL,
    user_agent TEXT NULL,
    payload LONGTEXT NOT NULL,
    last_activity INT NOT NULL,
    PRIMARY KEY (id),
    KEY sessions_user_id_index (user_id),
    KEY sessions_last_activity_index (last_activity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS personal_access_tokens (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(255) NOT NULL,
    token VARCHAR(64) NOT NULL,
    last_used_at TIMESTAMP NULL,
    created_at TIMESTAMP NULL,
    updated_at TIMESTAMP NULL,
    PRIMARY KEY (id),
    UNIQUE KEY personal_access_tokens_token_unique (token),
    KEY personal_access_tokens_user_id_foreign (user_id),
    CONSTRAINT personal_access_tokens_user_id_foreign FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS systems (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(255) NULL,
    environment VARCHAR(20) NULL,
    anydesk_id VARCHAR(100) NULL,
    anydesk_password TEXT NULL,
    rustdesk_id VARCHAR(100) NULL,
    rustdesk_password TEXT NULL,
    rdp_host VARCHAR(255) NULL,
    rdp_username VARCHAR(255) NULL,
    rdp_password TEXT NULL,
    ssh_host VARCHAR(255) NULL,
    ssh_username VARCHAR(255) NULL,
    ssh_password TEXT NULL,
    local_ip VARCHAR(255) NULL,
    local_username VARCHAR(255) NULL,
    local_password TEXT NULL,
    mysql_host VARCHAR(255) NULL,
    mysql_username VARCHAR(255) NULL,
    mysql_password TEXT NULL,
    domain VARCHAR(255) NULL,
    domain_username VARCHAR(255) NULL,
    domain_password TEXT NULL,
    vm_host VARCHAR(255) NULL,
    vm_username VARCHAR(255) NULL,
    vm_password TEXT NULL,
    notes TEXT NULL,
    created_at TIMESTAMP NULL,
    updated_at TIMESTAMP NULL,
    deleted_at TIMESTAMP NULL,
    PRIMARY KEY (id),
    KEY systems_name_index (name),
    KEY systems_environment_index (environment)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS system_status_checks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    system_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(255) NULL,
    http_status SMALLINT UNSIGNED NULL,
    response_time_ms INT UNSIGNED NULL,
    consecutive_failures INT UNSIGNED NOT NULL DEFAULT 0,
    checked_at TIMESTAMP NOT NULL,
    next_check_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NULL,
    updated_at TIMESTAMP NULL,
    PRIMARY KEY (id),
    UNIQUE KEY system_status_checks_system_id_unique (system_id),
    KEY system_status_checks_next_check_at_index (next_check_at),
    CONSTRAINT system_status_checks_system_id_foreign FOREIGN KEY (system_id) REFERENCES systems (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sign_in_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    signed_in_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    KEY sign_in_events_user_id_foreign (user_id),
    KEY sign_in_events_signed_in_at_index (signed_in_at),
    CONSTRAINT sign_in_events_user_id_foreign FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS notifications (
    id CHAR(36) NOT NULL,
    type VARCHAR(255) NOT NULL,
    notifiable_type VARCHAR(255) NOT NULL,
    notifiable_id BIGINT UNSIGNED NOT NULL,
    data TEXT NOT NULL,
    read_at TIMESTAMP NULL,
    created_at TIMESTAMP NULL,
    updated_at TIMESTAMP NULL,
    PRIMARY KEY (id),
    KEY notifications_notifiable_created_index (notifiable_type, notifiable_id, created_at),
    KEY notifications_notifiable_read_index (notifiable_type, notifiable_id, read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_records (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source VARCHAR(20) NOT NULL,
    external_id BIGINT UNSIGNED NOT NULL,
    occurred_at TIMESTAMP NULL,
    actor_name VARCHAR(255) NULL,
    user_level VARCHAR(255) NULL,
    field_office VARCHAR(255) NULL,
    module VARCHAR(255) NULL,
    action TEXT NULL,
    created_at TIMESTAMP NULL,
    updated_at TIMESTAMP NULL,
    PRIMARY KEY (id),
    UNIQUE KEY audit_records_source_external_id_unique (source, external_id),
    KEY audit_records_occurred_at_index (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_source_statuses (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source VARCHAR(20) NOT NULL,
    available TINYINT(1) NOT NULL DEFAULT 0,
    last_synced_at TIMESTAMP NULL,
    last_error VARCHAR(255) NULL,
    created_at TIMESTAMP NULL,
    updated_at TIMESTAMP NULL,
    PRIMARY KEY (id),
    UNIQUE KEY audit_source_statuses_source_unique (source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cms_audit_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    occurred_at TIMESTAMP NOT NULL,
    actor_id BIGINT UNSIGNED NULL,
    actor_name VARCHAR(255) NOT NULL,
    actor_username VARCHAR(255) NOT NULL,
    module VARCHAR(20) NOT NULL,
    action VARCHAR(40) NOT NULL,
    subject_type VARCHAR(20) NULL,
    subject_id BIGINT UNSIGNED NULL,
    subject_label VARCHAR(255) NULL,
    summary TEXT NOT NULL,
    details JSON NULL,
    created_at TIMESTAMP NULL,
    updated_at TIMESTAMP NULL,
    PRIMARY KEY (id),
    KEY cms_audit_events_occurred_at_index (occurred_at),
    KEY cms_audit_events_actor_id_foreign (actor_id),
    CONSTRAINT cms_audit_events_actor_id_foreign FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
