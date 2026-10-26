package ppa.cms.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SignInEventRepository extends JpaRepository<SignInEvent, Long> {
    List<SignInEvent> findAllByOrderBySignedInAtDescIdDesc(Pageable pageable);
    void deleteBySignedInAtBefore(Instant cutoff);
}
