package ppa.cms.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Lob;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "notifications")
public class StoredNotification {

    public static final String TYPE = "App\\Notifications\\SystemStatusChanged";
    public static final String NOTIFIABLE = "App\\Models\\User";

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String notifiableType;

    @Column(nullable = false)
    private Long notifiableId;

    @Lob
    @Column(nullable = false)
    private String data;

    private Instant readAt;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void creating() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void updating() {
        updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getNotifiableType() {
        return notifiableType;
    }

    public void setNotifiableType(String notifiableType) {
        this.notifiableType = notifiableType;
    }

    public Long getNotifiableId() {
        return notifiableId;
    }

    public void setNotifiableId(Long notifiableId) {
        this.notifiableId = notifiableId;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
