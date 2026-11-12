package ppa.cms.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ppa.cms.domain.CmsAuditAction;
import ppa.cms.domain.DataPermissionCatalog;
import ppa.cms.persistence.DataPermission;
import ppa.cms.persistence.DataPermissionRepository;
import ppa.cms.persistence.UserAccount;
import ppa.cms.persistence.UserRole;
import ppa.cms.persistence.UserRoleRepository;
import ppa.cms.security.Access;
import ppa.cms.support.PageSlice;
import ppa.cms.support.Texts;
import ppa.cms.support.TimeFormats;
import ppa.cms.web.ApiException;

@Service
public class UserRoleService {

    private final UserRoleRepository roles;
    private final DataPermissionRepository permissions;
    private final EntityManager entities;
    private final CmsAudit audit;

    public UserRoleService(UserRoleRepository roles, DataPermissionRepository permissions, EntityManager entities, CmsAudit audit) {
        this.roles = roles;
        this.permissions = permissions;
        this.entities = entities;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(UserAccount actor, HttpServletRequest request) {
        Access.requirePermission(actor, "can-view-user-roles", actor != null && actor.administrator());
        String search = bounded(request.getParameter("search"), 100);
        int page = page(request);
        int perPage = 20;
        long total = count(search);
        List<UserRole> items = query(search, page, perPage);
        PageSlice<UserRole> slice = new PageSlice<>(items, page, PageSlice.lastPage(total, perPage), perPage, total);
        return Map.of("data", items.stream().map(this::summary).toList(), "meta", AccountService.meta(slice));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> show(UserAccount actor, long id) {
        Access.requirePermission(actor, "can-view-user-role-details", actor != null && actor.administrator());
        return Map.of("data", detail(requireRole(id)));
    }

    @Transactional
    public Map<String, Object> create(UserAccount actor, Map<String, Object> body) {
        Access.requirePermission(actor, "can-create-user-role", actor != null && actor.administrator());
        NameInput input = nameInput(body, null);
        UserRole role = new UserRole();
        role.setName(input.name());
        role.setDescription(input.description());
        role.setCode(uniqueCode(input.name()));
        role.setBuiltIn(false);
        try {
            roles.save(role);
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.errors(Map.of("name", List.of("A role with this name already exists.")));
        }
        audit.record(actor, CmsAuditAction.ROLE_CREATED, "Created the user role " + role.getName() + ".", role.getName(), "user_role", role.getId(), null);
        return Map.of("message", "Role created: " + role.getName() + ".", "data", detail(role));
    }

    @Transactional
    public Map<String, Object> update(UserAccount actor, long id, Map<String, Object> body) {
        Access.requirePermission(actor, "can-update-user-role", actor != null && actor.administrator());
        UserRole role = requireRole(id);
        NameInput input = nameInput(body, role.getId());
        List<String> changed = new ArrayList<>();
        if (!role.getName().equals(input.name())) {
            changed.add("name");
        }
        if (differs(role.getDescription(), input.description())) {
            changed.add("description");
        }
        role.setName(input.name());
        role.setDescription(input.description());
        try {
            roles.save(role);
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.errors(Map.of("name", List.of("A role with this name already exists.")));
        }
        if (!changed.isEmpty()) {
            audit.record(actor, CmsAuditAction.ROLE_UPDATED, "Updated the user role " + role.getName() + ".", role.getName(), "user_role", role.getId(), Map.of("changed", changed));
        }
        String message = changed.isEmpty() ? "No changes were needed for " + role.getName() + "." : "Changes saved for " + role.getName() + ".";
        return Map.of("message", message, "data", detail(role));
    }

    @Transactional
    public Map<String, Object> delete(UserAccount actor, long id) {
        Access.requirePermission(actor, "can-delete-user-role", actor != null && actor.administrator());
        UserRole role = requireRole(id);
        if (role.isBuiltIn()) {
            throw ApiException.errors(Map.of("role", List.of("Administrator and User are built-in roles and can't be deleted.")));
        }
        String name = role.getName();
        roles.delete(role);
        audit.record(actor, CmsAuditAction.ROLE_REMOVED, "Deleted the user role " + name + ".", name, "user_role", id, null);
        return Map.of("message", "Role deleted: " + name + ".");
    }

    @Transactional
    public Map<String, Object> assign(UserAccount actor, long id, Map<String, Object> body) {
        Access.requirePermission(actor, "can-assign-role-permissions", actor != null && actor.administrator());
        UserRole role = requireRole(id);
        Set<String> requested = permissionCodes(body);
        List<DataPermission> found = requested.isEmpty() ? List.of() : permissions.findByCodeIn(requested);
        if (found.size() != requested.size()) {
            throw ApiException.errors(Map.of("permissions", List.of("One or more permissions are not recognized.")));
        }
        Set<String> before = role.getPermissions().stream().map(DataPermission::getCode).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        role.getPermissions().clear();
        role.getPermissions().addAll(found);
        roles.save(role);
        List<String> changed = new ArrayList<>();
        for (String code : requested) {
            if (!before.contains(code)) {
                changed.add("added " + code);
            }
        }
        for (String code : before) {
            if (!requested.contains(code)) {
                changed.add("removed " + code);
            }
        }
        if (!changed.isEmpty()) {
            audit.record(actor, CmsAuditAction.ROLE_PERMISSIONS_ASSIGNED, "Assigned permissions to the user role " + role.getName() + ".", role.getName(), "user_role", role.getId(), Map.of("changed", changed));
        }
        String message = changed.isEmpty()
                ? "No permission changes were needed for " + role.getName() + "."
                : "Permissions saved for " + role.getName() + ".";
        return Map.of("message", message, "data", detail(role));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> catalog(UserAccount actor) {
        boolean canAssign = Access.allowed(actor, "can-assign-role-permissions", actor != null && actor.administrator());
        boolean canView = Access.allowed(actor, "can-view-user-role-details", actor != null && actor.administrator());
        if (!canAssign && !canView) {
            throw ApiException.message(403, "This action is unauthorized.");
        }
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (DataPermission permission : permissions.findAllByOrderBySortOrderAsc()) {
            Map<String, Object> group = groups.computeIfAbsent(permission.getModule(), key -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("module", permission.getModule());
                created.put("label", permission.getModuleLabel());
                created.put("permissions", new ArrayList<Map<String, Object>>());
                return created;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) group.get("permissions");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", permission.getCode());
            row.put("label", permission.getLabel());
            row.put("description", permission.getDescription());
            items.add(row);
        }
        return Map.of("data", new ArrayList<>(groups.values()));
    }

    private UserRole requireRole(long id) {
        return roles.findDetailedById(id).orElseThrow(() -> ApiException.message(404, "User role not found."));
    }

    private Map<String, Object> summary(UserRole role) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", role.getId());
        row.put("name", role.getName());
        row.put("description", role.getDescription());
        row.put("code", role.getCode());
        row.put("built_in", role.isBuiltIn());
        row.put("permission_count", role.getPermissions().size());
        row.put("created_at", TimeFormats.iso(role.getCreatedAt()));
        row.put("updated_at", TimeFormats.iso(role.getUpdatedAt()));
        return row;
    }

    private Map<String, Object> detail(UserRole role) {
        Map<String, Object> row = summary(role);
        List<Map<String, Object>> assigned = role.getPermissions().stream()
                .sorted(Comparator.comparingInt(DataPermission::getSortOrder))
                .map(permission -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", permission.getCode());
                    item.put("label", permission.getLabel());
                    item.put("description", permission.getDescription());
                    item.put("module", permission.getModule());
                    item.put("module_label", permission.getModuleLabel());
                    return item;
                })
                .toList();
        row.put("permissions", assigned);
        return row;
    }

    private NameInput nameInput(Map<String, Object> body, Long id) {
        String name = Texts.namePart(body.get("name"));
        String description = Texts.text(body.get("description"));
        Map<String, List<String>> errors = Texts.errors();
        if (name == null) {
            Texts.add(errors, "name", "Enter a role name.");
        } else if (name.length() > 80) {
            Texts.add(errors, "name", "Role name must be 80 characters or fewer.");
        } else if (roles.nameTaken(name, id)) {
            Texts.add(errors, "name", "A role with this name already exists.");
        }
        if (description != null && description.length() > 255) {
            Texts.add(errors, "description", "Description must be 255 characters or fewer.");
        }
        Texts.finish(errors);
        return new NameInput(name, description);
    }

    private Set<String> permissionCodes(Map<String, Object> body) {
        Object raw = body.get("permissions");
        if (!(raw instanceof List<?> list)) {
            throw ApiException.errors(Map.of("permissions", List.of("Choose the permissions for this role.")));
        }
        Set<String> codes = new LinkedHashSet<>();
        for (Object value : list) {
            String code = Texts.text(value);
            if (code == null) {
                continue;
            }
            boolean known = DataPermissionCatalog.all().stream().anyMatch(item -> item.code().equals(code));
            if (!known) {
                throw ApiException.errors(Map.of("permissions", List.of("One or more permissions are not recognized.")));
            }
            codes.add(code);
        }
        return codes;
    }

    private String uniqueCode(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isEmpty()) {
            base = "role";
        }
        if (base.length() > 36) {
            base = base.substring(0, 36).replaceAll("-$", "");
        }
        String code = base;
        int suffix = 2;
        while (roles.existsByCode(code)) {
            code = base + "-" + suffix;
            suffix++;
        }
        return code;
    }

    private long count(String search) {
        CriteriaBuilder builder = entities.getCriteriaBuilder();
        var query = builder.createQuery(Long.class);
        Root<UserRole> root = query.from(UserRole.class);
        query.select(builder.count(root)).where(predicates(builder, root, search));
        return entities.createQuery(query).getSingleResult();
    }

    private List<UserRole> query(String search, int page, int perPage) {
        CriteriaBuilder builder = entities.getCriteriaBuilder();
        var query = builder.createQuery(UserRole.class);
        Root<UserRole> root = query.from(UserRole.class);
        query.where(predicates(builder, root, search))
                .orderBy(builder.desc(root.get("builtIn")), builder.asc(root.get("name")), builder.asc(root.get("id")));
        return entities.createQuery(query).setFirstResult((page - 1) * perPage).setMaxResults(perPage).getResultList();
    }

    private Predicate[] predicates(CriteriaBuilder builder, Root<UserRole> root, String search) {
        if (search == null) {
            return new Predicate[0];
        }
        String like = Texts.likeContains(search.toLowerCase(Locale.ROOT));
        return new Predicate[] {builder.or(
                builder.like(builder.lower(root.get("name")), like, '\\'),
                builder.like(builder.lower(builder.coalesce(root.get("description"), "")), like, '\\')
        )};
    }

    private static int page(HttpServletRequest request) {
        String value = request.getParameter("page");
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            int page = Integer.parseInt(value);
            if (page < 1) {
                throw ApiException.errors(Map.of("page", List.of("The page field must be at least 1.")));
            }
            return page;
        } catch (NumberFormatException exception) {
            throw ApiException.errors(Map.of("page", List.of("The page field must be an integer.")));
        }
    }

    private static String bounded(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.length() > max) {
            throw ApiException.errors(Map.of("search", List.of("Search must be " + max + " characters or fewer.")));
        }
        return text;
    }

    private static boolean differs(String left, String right) {
        return left == null ? right != null : !left.equals(right);
    }

    private record NameInput(String name, String description) {
    }
}
