package ppa.cms.service;

import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import ppa.cms.security.Access;
import org.springframework.transaction.annotation.Transactional;
import ppa.cms.domain.DataPermissionCatalog;
import ppa.cms.persistence.UserAccount;
import ppa.cms.persistence.UserRoleRepository;
import ppa.cms.security.PermissionLookup;

@Service
public class PermissionService implements PermissionLookup {

    private final UserRoleRepository roles;

    public PermissionService(UserRoleRepository roles) {
        this.roles = roles;
    }

    @PostConstruct
    void register() {
        Access.use(this);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean roleReady(UserAccount user) {
        return user != null && user.getRole() != null && roles.existsByCode(user.getRole());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean allows(UserAccount user, String code) {
        if (user == null || user.getRole() == null) {
            return false;
        }
        return roles.findDetailedByCode(user.getRole())
                .map(role -> role.getPermissions().stream().anyMatch(permission -> code.equals(permission.getCode())))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<String> codesFor(UserAccount user) {
        if (user == null || user.disabled() || user.getRole() == null) {
            return List.of();
        }
        return roles.findDetailedByCode(user.getRole())
                .map(role -> role.getPermissions().stream()
                        .map(permission -> permission.getCode())
                        .sorted(Comparator.naturalOrder())
                        .toList())
                .orElseGet(() -> DataPermissionCatalog.all().stream()
                        .filter(item -> user.administrator() || item.userDefault())
                        .map(DataPermissionCatalog.Item::code)
                        .toList());
    }
}
