package ppa.cms.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataPermissionRepository extends JpaRepository<DataPermission, Long> {

    Optional<DataPermission> findByCode(String code);

    List<DataPermission> findByCodeIn(Collection<String> codes);

    List<DataPermission> findAllByOrderBySortOrderAsc();
}
