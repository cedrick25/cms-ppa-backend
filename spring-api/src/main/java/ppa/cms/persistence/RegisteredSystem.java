package ppa.cms.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Lob;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.SQLRestriction;
import ppa.cms.domain.SystemEnvironment;

@Entity
@Table(name = "systems")
@SQLRestriction("deleted_at is null")
public class RegisteredSystem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    private String description;

    @Column(length = 20)
    private SystemEnvironment environment;

    @Column(length = 100)
    private String anydeskId;

    @Lob
    private String anydeskPassword;

    @Column(length = 100)
    private String rustdeskId;

    @Lob
    private String rustdeskPassword;

    private String rdpHost;
    private String rdpUsername;

    @Lob
    private String rdpPassword;

    private String sshHost;
    private String sshUsername;

    @Lob
    private String sshPassword;

    private String localIp;
    private String localUsername;

    @Lob
    private String localPassword;

    private String mysqlHost;
    private String mysqlUsername;

    @Lob
    private String mysqlPassword;

    private String domain;
    private String domainUsername;

    @Lob
    private String domainPassword;

    private String vmHost;
    private String vmUsername;

    @Lob
    private String vmPassword;

    @Lob
    private String notes;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    @OneToOne(mappedBy = "system")
    private StatusCheck statusCheck;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public SystemEnvironment getEnvironment() {
        return environment;
    }

    public void setEnvironment(SystemEnvironment environment) {
        this.environment = environment;
    }

    public String getAnydeskId() {
        return anydeskId;
    }

    public void setAnydeskId(String anydeskId) {
        this.anydeskId = anydeskId;
    }

    public String getAnydeskPassword() {
        return anydeskPassword;
    }

    public void setAnydeskPassword(String anydeskPassword) {
        this.anydeskPassword = anydeskPassword;
    }

    public String getRustdeskId() {
        return rustdeskId;
    }

    public void setRustdeskId(String rustdeskId) {
        this.rustdeskId = rustdeskId;
    }

    public String getRustdeskPassword() {
        return rustdeskPassword;
    }

    public void setRustdeskPassword(String rustdeskPassword) {
        this.rustdeskPassword = rustdeskPassword;
    }

    public String getRdpHost() {
        return rdpHost;
    }

    public void setRdpHost(String rdpHost) {
        this.rdpHost = rdpHost;
    }

    public String getRdpUsername() {
        return rdpUsername;
    }

    public void setRdpUsername(String rdpUsername) {
        this.rdpUsername = rdpUsername;
    }

    public String getRdpPassword() {
        return rdpPassword;
    }

    public void setRdpPassword(String rdpPassword) {
        this.rdpPassword = rdpPassword;
    }

    public String getSshHost() {
        return sshHost;
    }

    public void setSshHost(String sshHost) {
        this.sshHost = sshHost;
    }

    public String getSshUsername() {
        return sshUsername;
    }

    public void setSshUsername(String sshUsername) {
        this.sshUsername = sshUsername;
    }

    public String getSshPassword() {
        return sshPassword;
    }

    public void setSshPassword(String sshPassword) {
        this.sshPassword = sshPassword;
    }

    public String getLocalIp() {
        return localIp;
    }

    public void setLocalIp(String localIp) {
        this.localIp = localIp;
    }

    public String getLocalUsername() {
        return localUsername;
    }

    public void setLocalUsername(String localUsername) {
        this.localUsername = localUsername;
    }

    public String getLocalPassword() {
        return localPassword;
    }

    public void setLocalPassword(String localPassword) {
        this.localPassword = localPassword;
    }

    public String getMysqlHost() {
        return mysqlHost;
    }

    public void setMysqlHost(String mysqlHost) {
        this.mysqlHost = mysqlHost;
    }

    public String getMysqlUsername() {
        return mysqlUsername;
    }

    public void setMysqlUsername(String mysqlUsername) {
        this.mysqlUsername = mysqlUsername;
    }

    public String getMysqlPassword() {
        return mysqlPassword;
    }

    public void setMysqlPassword(String mysqlPassword) {
        this.mysqlPassword = mysqlPassword;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getDomainUsername() {
        return domainUsername;
    }

    public void setDomainUsername(String domainUsername) {
        this.domainUsername = domainUsername;
    }

    public String getDomainPassword() {
        return domainPassword;
    }

    public void setDomainPassword(String domainPassword) {
        this.domainPassword = domainPassword;
    }

    public String getVmHost() {
        return vmHost;
    }

    public void setVmHost(String vmHost) {
        this.vmHost = vmHost;
    }

    public String getVmUsername() {
        return vmUsername;
    }

    public void setVmUsername(String vmUsername) {
        this.vmUsername = vmUsername;
    }

    public String getVmPassword() {
        return vmPassword;
    }

    public void setVmPassword(String vmPassword) {
        this.vmPassword = vmPassword;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public StatusCheck getStatusCheck() {
        return statusCheck;
    }

    public void setStatusCheck(StatusCheck statusCheck) {
        this.statusCheck = statusCheck;
    }
}
