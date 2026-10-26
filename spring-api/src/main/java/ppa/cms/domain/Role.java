package ppa.cms.domain;

public enum Role {
    ADMINISTRATOR("administrator", "Administrator"),
    USER("user", "User");

    private final String value;
    private final String label;

    Role(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String value() {
        return value;
    }

    public String label() {
        return label;
    }

    public static Role from(String value) {
        if (value == null) {
            return null;
        }
        for (Role role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        return null;
    }
}
