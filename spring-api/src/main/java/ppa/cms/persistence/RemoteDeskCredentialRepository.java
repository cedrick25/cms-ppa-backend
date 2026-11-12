package ppa.cms.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RemoteDeskCredentialRepository extends JpaRepository<RemoteDeskCredential, Long> {
    List<RemoteDeskCredential> findByUserId(Long userId);
}
