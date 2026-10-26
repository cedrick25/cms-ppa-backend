package ppa.cms.domain;

public enum Reachability {
    UP("up"),
    DOWN("down"),
    UNKNOWN("unknown");

    private final String value;

    Reachability(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
