package ppa.cms.service;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ppa.cms.domain.DataPermissionCatalog;
import ppa.cms.persistence.DataPermission;
import ppa.cms.persistence.DataPermissionRepository;
import ppa.cms.persistence.UserRole;
import ppa.cms.persistence.UserRoleRepository;

@Component
@Order(1)
public class DataPermissionSeeder implements ApplicationRunner {

    private final DataPermissionRepository permissions;
    private final UserRoleRepository roles;

    public DataPermissionSeeder(DataPermissionRepository permissions, UserRoleRepository roles) {
        this.permissions = permissions;
        this.roles = roles;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Set<DataPermission> all = new LinkedHashSet<>();
        Set<DataPermission> userDefaults = new LinkedHashSet<>();
        int sort = 0;
        for (DataPermissionCatalog.Item item : DataPermissionCatalog.all()) {
            DataPermission permission = permissions.findByCode(item.code()).orElseGet(DataPermission::new);
            permission.setCode(item.code());
            permission.setModule(item.module());
            permission.setModuleLabel(item.moduleLabel());
            permission.setLabel(item.label());
            permission.setDescription(item.description());
            permission.setSortOrder(sort++);
            permission = permissions.save(permission);
            all.add(permission);
            if (item.userDefault()) {
                userDefaults.add(permission);
            }
        }
        ensureBuiltIn("administrator", "Administrator", "Full access to every CMS-PPA module.", all);
        ensureBuiltIn("user", "User", "Dashboard, own account, and credentials access.", userDefaults);
    }

    private void ensureBuiltIn(String code, String name, String description, Set<DataPermission> grants) {
        if (roles.existsByCode(code)) {
            return;
        }
        UserRole role = new UserRole();
        role.setCode(code);
        role.setName(name);
        role.setDescription(description);
        role.setBuiltIn(true);
        role.setPermissions(new LinkedHashSet<>(grants));
        roles.save(role);
    }
}
