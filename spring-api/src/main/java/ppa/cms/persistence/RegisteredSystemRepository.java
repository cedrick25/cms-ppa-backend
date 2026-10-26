package ppa.cms.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ppa.cms.domain.SystemEnvironment;

public interface RegisteredSystemRepository extends JpaRepository<RegisteredSystem, Long> {
    boolean existsByName(String name);
    List<RegisteredSystem> findByEnvironmentOrderByNameAscIdAsc(SystemEnvironment environment);
}
