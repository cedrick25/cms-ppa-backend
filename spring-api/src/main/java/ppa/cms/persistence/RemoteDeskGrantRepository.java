package ppa.cms.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RemoteDeskGrantRepository extends JpaRepository<RemoteDeskGrant, Long> {
    Optional<RemoteDeskGrant> findByTokenHash(String tokenHash);
}
