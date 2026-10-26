package ppa.cms.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, Long> {
    Optional<AuditRecord> findBySourceAndExternalId(String source, long externalId);
    long countBySource(String source);
}
