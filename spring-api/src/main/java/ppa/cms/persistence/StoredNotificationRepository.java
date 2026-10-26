package ppa.cms.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredNotificationRepository extends JpaRepository<StoredNotification, String> {
    List<StoredNotification> findByTypeAndNotifiableTypeAndNotifiableIdOrderByCreatedAtDesc(String type, String notifiableType, Long notifiableId, Pageable pageable);
    List<StoredNotification> findByTypeAndNotifiableTypeAndNotifiableIdAndReadAtIsNull(String type, String notifiableType, Long notifiableId);
    void deleteByNotifiableTypeAndNotifiableId(String notifiableType, Long notifiableId);
    void deleteByReadAtBeforeOrCreatedAtBefore(Instant readBefore, Instant createdBefore);
}
