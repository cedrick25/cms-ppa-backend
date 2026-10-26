package ppa.cms.domain;

public enum CmsAuditAction {
    SIGNED_IN("signed_in", "Signed in", CmsAuditModule.SESSION),
    SIGNED_OUT("signed_out", "Signed out", CmsAuditModule.SESSION),
    REMOTE_DESK_OPENED("remote_desk_opened", "Opened remote desk", CmsAuditModule.CREDENTIALS),
    CREDENTIALS_REGISTERED("credentials_registered", "Registered credentials", CmsAuditModule.CREDENTIALS),
    CREDENTIALS_UPDATED("credentials_updated", "Updated credentials", CmsAuditModule.CREDENTIALS),
    CREDENTIALS_REMOVED("credentials_removed", "Removed credentials", CmsAuditModule.CREDENTIALS),
    USER_REGISTERED("user_registered", "Registered account", CmsAuditModule.USERS),
    USER_UPDATED("user_updated", "Updated account", CmsAuditModule.USERS),
    USER_DISABLED("user_disabled", "Disabled account", CmsAuditModule.USERS),
    USER_REMOVED("user_removed", "Removed account", CmsAuditModule.USERS);

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
