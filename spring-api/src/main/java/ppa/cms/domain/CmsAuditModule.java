package ppa.cms.domain;

public enum CmsAuditModule {
    SESSION("session", "Sign-in"),
    CREDENTIALS("credentials", "Credentials"),
    USERS("users", "Users");

    private final String value;
    private final String label;

    CmsAuditModule(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String value() {
        return value;
    }

    public String label() {
        return label;
    }
}
