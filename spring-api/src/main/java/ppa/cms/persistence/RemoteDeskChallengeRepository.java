package ppa.cms.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RemoteDeskChallengeRepository extends JpaRepository<RemoteDeskChallenge, Long> {
    List<RemoteDeskChallenge> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
