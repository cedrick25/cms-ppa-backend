package ppa.cms.domain;

public enum SystemEnvironment {
    PRODUCTION("production"),
    TEST("test"),
    LEGACY("legacy");

    private final String value;

    SystemEnvironment(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SystemEnvironment from(String value) {
        if (value == null) {
            return null;
        }
        for (SystemEnvironment environment : values()) {
            if (environment.value.equals(value)) {
                return environment;
            }
        }
        return null;
    }
}
