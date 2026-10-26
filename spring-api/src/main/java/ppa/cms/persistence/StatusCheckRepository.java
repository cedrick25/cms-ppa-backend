package ppa.cms.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatusCheckRepository extends JpaRepository<StatusCheck, Long> {
    Optional<StatusCheck> findBySystem_Id(Long systemId);
    void deleteBySystem_Id(Long systemId);
}
