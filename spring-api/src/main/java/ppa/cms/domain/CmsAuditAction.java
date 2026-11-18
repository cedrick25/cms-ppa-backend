package ppa.cms.domain;

public enum CmsAuditAction {
    SIGNED_IN("signed_in", "Signed in", CmsAuditModule.SESSION),
    SIGNED_OUT("signed_out", "Signed out", CmsAuditModule.SESSION),
    REMOTE_DESK_OPENED("remote_desk_opened", "Opened remote desk", CmsAuditModule.CREDENTIALS),
    REMOTE_CREDENTIALS_VIEWED("remote_credentials_viewed", "Viewed remote credentials", CmsAuditModule.CREDENTIALS),
    REMOTE_CREDENTIALS_UPDATED("remote_credentials_updated", "Updated remote credentials", CmsAuditModule.CREDENTIALS),
    REMOTE_PASSWORD_COPIED("remote_password_copied", "Copied remote password", CmsAuditModule.CREDENTIALS),
    SYSTEM_NAME_UPDATED("system_name_updated", "Updated system name", CmsAuditModule.CREDENTIALS),
    MYSQL_CREDENTIALS_VIEWED("mysql_credentials_viewed", "Viewed MySQL credentials", CmsAuditModule.CREDENTIALS),
    MYSQL_CREDENTIALS_COPIED("mysql_credentials_copied", "Copied MySQL credentials", CmsAuditModule.CREDENTIALS),
    MYSQL_CREDENTIALS_UPDATED("mysql_credentials_updated", "Updated MySQL credentials", CmsAuditModule.CREDENTIALS),
    MYSQL_CREDENTIALS_REMOVED("mysql_credentials_removed", "Removed MySQL credentials", CmsAuditModule.CREDENTIALS),
    LOCAL_IP_COPIED("local_ip_copied", "Copied local IP", CmsAuditModule.CREDENTIALS),
    DOMAIN_COPIED("domain_copied", "Copied domain", CmsAuditModule.CREDENTIALS),
    VM_CREDENTIALS_COPIED("vm_credentials_copied", "Copied VM credentials", CmsAuditModule.CREDENTIALS),
    CREDENTIALS_VIEWED("credentials_viewed", "Viewed credentials", CmsAuditModule.CREDENTIALS),
    CREDENTIALS_REVEALED("credentials_revealed", "Revealed system row", CmsAuditModule.CREDENTIALS),
    CREDENTIALS_REGISTERED("credentials_registered", "Registered credentials", CmsAuditModule.CREDENTIALS),
    CREDENTIALS_UPDATED("credentials_updated", "Updated credentials", CmsAuditModule.CREDENTIALS),
    CREDENTIALS_REMOVED("credentials_removed", "Removed credentials", CmsAuditModule.CREDENTIALS),
    USER_REGISTERED("user_registered", "Registered account", CmsAuditModule.USERS),
    USER_UPDATED("user_updated", "Updated account", CmsAuditModule.USERS),
    USER_DISABLED("user_disabled", "Disabled account", CmsAuditModule.USERS),
    USER_REMOVED("user_removed", "Removed account", CmsAuditModule.USERS),
    ROLE_CREATED("role_created", "Created role", CmsAuditModule.USER_ROLES),
    ROLE_UPDATED("role_updated", "Updated role", CmsAuditModule.USER_ROLES),
    ROLE_REMOVED("role_removed", "Deleted role", CmsAuditModule.USER_ROLES),
    ROLE_PERMISSIONS_ASSIGNED("role_permissions_assigned", "Assigned permissions", CmsAuditModule.USER_ROLES);

    private final String value;
    private final String label;
    private final CmsAuditModule module;

    CmsAuditAction(String value, String label, CmsAuditModule module) {
        this.value = value;
        this.label = label;
        this.module = module;
    }

    public String value() {
        return value;
    }

    public String label() {
        return label;
    }

    public CmsAuditModule module() {
        return module;
    }
}
