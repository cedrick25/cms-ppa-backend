package ppa.cms.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    boolean existsByCode(String code);

    @Query("select distinct role from UserRole role left join fetch role.permissions where role.code = :code")
    Optional<UserRole> findDetailedByCode(@Param("code") String code);

    @Query("select distinct role from UserRole role left join fetch role.permissions where role.id = :id")
    Optional<UserRole> findDetailedById(@Param("id") Long id);

    @Query("select count(role) > 0 from UserRole role where lower(role.name) = lower(:name) and (:id is null or role.id <> :id)")
    boolean nameTaken(@Param("name") String name, @Param("id") Long id);

    boolean existsByCodeAndIdNot(String code, Long id);
}
