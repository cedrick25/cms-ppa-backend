package ppa.cms.domain;

public enum AuditSource {
    UAMS("uams", "UAMS"),
    CMIS("cmis", "CMIS"),
    PPIS("ppis", "PPIS");

    private final String value;
    private final String label;

    AuditSource(String value, String label) {
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
