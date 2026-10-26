package ppa.cms.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {
    Optional<ApiToken> findByToken(String token);
    void deleteByUserId(Long userId);
    void deleteByUserIdAndIdNot(Long userId, Long id);
}
