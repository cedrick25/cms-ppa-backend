package ppa.cms.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import ppa.cms.domain.Reachability;

@Entity
@Table(name = "system_status_checks")
public class StatusCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "system_id", nullable = false, unique = true)
    private RegisteredSystem system;

    @Column(nullable = false, length = 20)
    private Reachability status;

    private String reason;

    private Integer httpStatus;

    private Integer responseTimeMs;

    @Column(nullable = false)
    private int consecutiveFailures;

    @Column(nullable = false)
    private Instant checkedAt;

    @Column(nullable = false)
    private Instant nextCheckAt;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void creating() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void updating() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public RegisteredSystem getSystem() {
        return system;
    }

    public void setSystem(RegisteredSystem system) {
        this.system = system;
    }

    public Reachability getStatus() {
        return status;
    }

    public void setStatus(Reachability status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public Integer getResponseTimeMs() {
        return responseTimeMs;
    }

    public void setResponseTimeMs(Integer responseTimeMs) {
        this.responseTimeMs = responseTimeMs;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(Instant checkedAt) {
        this.checkedAt = checkedAt;
    }

    public Instant getNextCheckAt() {
        return nextCheckAt;
    }

    public void setNextCheckAt(Instant nextCheckAt) {
        this.nextCheckAt = nextCheckAt;
    }
}
