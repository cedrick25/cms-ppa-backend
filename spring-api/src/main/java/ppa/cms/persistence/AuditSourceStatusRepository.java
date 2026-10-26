package ppa.cms.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditSourceStatusRepository extends JpaRepository<AuditSourceStatus, Long> {
    Optional<AuditSourceStatus> findBySource(String source);
}
