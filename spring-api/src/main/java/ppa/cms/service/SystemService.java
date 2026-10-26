package ppa.cms.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ppa.cms.crypto.LaravelEncrypter;
import ppa.cms.domain.CmsAuditAction;
import ppa.cms.domain.SystemEnvironment;
import ppa.cms.persistence.RegisteredSystem;
import ppa.cms.persistence.RegisteredSystemRepository;
import ppa.cms.persistence.StatusCheckRepository;
import ppa.cms.persistence.UserAccount;
import ppa.cms.security.Access;
import ppa.cms.support.Addresses;
import ppa.cms.support.PageSlice;
import ppa.cms.support.Texts;
import ppa.cms.support.TimeFormats;
import ppa.cms.web.ApiException;

@Service
public class SystemService {

    private static final Logger log = LoggerFactory.getLogger(SystemService.class);
    private static final List<String> SEARCHABLE = List.of(
            "name", "description", "anydeskId", "rustdeskId", "rdpHost", "sshHost", "localIp", "mysqlHost", "domain", "vmHost"
    );
    private static final List<String> FIELDS = List.of(
            "name", "description", "environment",
            "anydesk_id", "anydesk_password", "rustdesk_id", "rustdesk_password",
            "rdp_host", "rdp_username", "rdp_password", "ssh_host", "ssh_username", "ssh_password",
            "local_ip", "local_username", "local_password", "mysql_host", "mysql_username", "mysql_password",
            "domain", "domain_username", "domain_password", "vm_host", "vm_username", "vm_password",
            "notes"
    );
    private static final List<String> SECRETS = List.of(
            "anydesk_password", "rustdesk_password", "rdp_password", "ssh_password",
            "local_password", "mysql_password", "domain_password", "vm_password", "notes"
    );

    private final RegisteredSystemRepository systems;
    private final StatusCheckRepository checks;
    private final EntityManager entities;
    private final LaravelEncrypter encrypter;
    private final CmsAudit audit;

    public SystemService(
            RegisteredSystemRepository systems,
            StatusCheckRepository checks,
            EntityManager entities,
            LaravelEncrypter encrypter,
            CmsAudit audit
    ) {
        this.systems = systems;
        this.checks = checks;
        this.entities = entities;
        this.encrypter = encrypter;
        this.audit = audit;
    }

    public Map<String, Object> list(UserAccount actor, HttpServletRequest request) {
        Access.requireManageSystems(actor);
        String search = bounded(request.getParameter("search"), 100);
        String environment = request.getParameter("environment");
        if (environment == null || environment.isBlank()) {
            environment = "all";
        }
        if (!environment.equals("all") && SystemEnvironment.from(environment) == null) {
            throw ApiException.errors(Map.of("environment", List.of("Choose Production, Test, or Legacy.")));
        }
        int page = page(request);
        int perPage = 20;
        long total = count(search, environment);
        List<RegisteredSystem> items = query(search, environment, page, perPage);
        PageSlice<RegisteredSystem> slice = new PageSlice<>(items, page, PageSlice.lastPage(total, perPage), perPage, total);
        Map<String, Object> meta = AccountService.meta(slice);
        meta.put("total_registered", systems.count());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("all", count(search, "all"));
        for (SystemEnvironment value : SystemEnvironment.values()) {
            counts.put(value.value(), count(search, value.value()));
        }
        return Map.of(
                "data", items.stream().map(this::summary).toList(),
                "meta", meta,
                "counts", counts
        );
    }

    public Map<String, Object> show(UserAccount actor, long id) {
        Access.requireManageSystems(actor);
        RegisteredSystem system = require(id);
        log.info("System credentials viewed. system_id={} performed_by={}", system.getId(), actor.getUsername());
        return Map.of("system", detail(system));
    }

    @Transactional
    public Map<String, Object> store(UserAccount actor, Map<String, Object> body) {
        Access.requireManageSystems(actor);
        Map<String, String> plain = validate(blank(), body, null);
        RegisteredSystem system = new RegisteredSystem();
        write(system, plain);
        systems.save(system);
        log.info("System credentials registered. system_id={} performed_by={}", system.getId(), actor.getUsername());
        audit.record(actor, CmsAuditAction.CREDENTIALS_REGISTERED, "Registered credentials for " + system.getName() + ".", system.getName(), "system", system.getId(), null);
        return Map.of("message", "System registered: " + system.getName() + ".", "system", detail(system));
    }

    @Transactional
    public Map<String, Object> update(UserAccount actor, long id, Map<String, Object> body) {
        Access.requireManageSystems(actor);
        RegisteredSystem system = require(id);
        Map<String, String> before = readPlain(system);
        Map<String, String> plain = validate(before, body, system.getId());
        List<String> changed = new ArrayList<>();
        for (String field : FIELDS) {
            if (!java.util.Objects.equals(before.get(field), plain.get(field))) {
                changed.add(field);
            }
        }
        String previousDomain = system.getDomain();
        write(system, plain);
        systems.save(system);
        if (!java.util.Objects.equals(previousDomain, system.getDomain())) {
            checks.deleteBySystem_Id(system.getId());
            system.setStatusCheck(null);
        }
        if (!changed.isEmpty()) {
            log.info("System credentials updated. system_id={} performed_by={}", system.getId(), actor.getUsername());
            audit.record(actor, CmsAuditAction.CREDENTIALS_UPDATED, "Updated credentials for " + system.getName() + ".", system.getName(), "system", system.getId(), Map.of("changed", changed));
        }
        String message = changed.isEmpty() ? "No changes were needed for " + system.getName() + "." : "Changes saved for " + system.getName() + ".";
        return Map.of("message", message, "system", detail(system));
    }

    @Transactional
    public Map<String, Object> destroy(UserAccount actor, long id) {
        Access.requireManageSystems(actor);
        RegisteredSystem system = require(id);
        system.setDeletedAt(Instant.now());
        systems.save(system);
        log.info("System credentials removed. system_id={} performed_by={}", system.getId(), actor.getUsername());
        audit.record(actor, CmsAuditAction.CREDENTIALS_REMOVED, "Removed credentials for " + system.getName() + ".", system.getName(), "system", system.getId(), null);
        return Map.of("message", "System removed: " + system.getName() + ".");
    }

    @Transactional
    public Map<String, Object> openRemoteDesk(UserAccount actor, long id, Map<String, Object> body) {
        Access.requireManageSystems(actor);
        RegisteredSystem system = require(id);
        String type = Texts.text(body.get("type"));
        if (type == null) {
            throw ApiException.errors(Map.of("type", List.of("Choose a remote desk to open.")));
        }
        if (!List.of("anydesk", "rustdesk", "rdp", "ssh").contains(type)) {
            throw ApiException.errors(Map.of("type", List.of("Choose AnyDesk, RustDesk, RDP, or SSH.")));
        }
        Map<String, Object> entry = null;
        for (Map<String, Object> candidate : remoteAccess(system)) {
            if (type.equals(candidate.get("type"))) {
                entry = candidate;
                break;
            }
        }
        if (entry == null) {
            String label = switch (type) {
                case "anydesk" -> "AnyDesk";
                case "rustdesk" -> "RustDesk";
                case "rdp" -> "RDP";
                default -> "SSH";
            };
            throw ApiException.errors(Map.of("type", List.of("This system has no " + label + " remote desk.")));
        }
        String desk = String.valueOf(entry.get("label"));
        audit.record(actor, CmsAuditAction.REMOTE_DESK_OPENED, "Opened the " + desk + " remote desk for " + system.getName() + ".", system.getName(), "system", system.getId(), Map.of("remote_desk", desk));
        return Map.of("message", "Opened the " + desk + " remote desk for " + system.getName() + ".");
    }

    public RegisteredSystem require(long id) {
        return systems.findById(id).orElseThrow(() -> ApiException.message(404, "Not found."));
    }

    public void validateImported(Map<String, String> attributes) {
        Map<String, Object> body = new LinkedHashMap<>();
        attributes.forEach(body::put);
        validate(blank(), body, null);
    }

    public void saveImported(Map<String, String> plain) {
        RegisteredSystem system = new RegisteredSystem();
        write(system, plain);
        systems.save(system);
    }

    public boolean nameTaken(String name) {
        return systems.existsByName(name);
    }

    private Map<String, String> validate(Map<String, String> current, Map<String, Object> body, Long ignoreId) {
        Map<String, String> plain = new LinkedHashMap<>(current);
        for (String field : FIELDS) {
            if (body.containsKey(field)) {
                plain.put(field, Texts.text(body.get(field)));
            }
        }
        Map<String, List<String>> errors = Texts.errors();
        String name = plain.get("name");
        if (name == null) {
            Texts.add(errors, "name", "Enter the system name.");
        } else if (name.length() > 100) {
            Texts.add(errors, "name", "System name must be 100 characters or fewer.");
        } else if (nameTakenByOther(name, ignoreId)) {
            Texts.add(errors, "name", Texts.NAME_TAKEN);
        }
        if (plain.get("description") != null && plain.get("description").length() > 255) {
            Texts.add(errors, "description", "Description must be 255 characters or fewer.");
        }
        if (plain.get("environment") != null && SystemEnvironment.from(plain.get("environment")) == null) {
            Texts.add(errors, "environment", "Choose Production, Test, or Legacy.");
        }
        if (plain.get("notes") != null && plain.get("notes").length() > 2000) {
            Texts.add(errors, "notes", "Notes must be 2,000 characters or fewer.");
        }
        for (String field : FIELDS) {
            if (field.equals("name") || field.equals("description") || field.equals("environment") || field.equals("notes")) {
                continue;
            }
            String value = plain.get(field);
            int max = field.endsWith("_id") ? 100 : 255;
            if (value != null && value.length() > max) {
                Texts.add(errors, field, label(field) + " must be " + max + " characters or fewer.");
            }
        }
        Texts.finish(errors);
        return plain;
    }

    private boolean nameTakenByOther(String name, Long ignoreId) {
        CriteriaBuilder builder = entities.getCriteriaBuilder();
        var query = builder.createQuery(Long.class);
        Root<RegisteredSystem> root = query.from(RegisteredSystem.class);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(root.get("name"), name));
        if (ignoreId != null) {
            predicates.add(builder.notEqual(root.get("id"), ignoreId));
        }
        query.select(builder.count(root)).where(predicates.toArray(Predicate[]::new));
        return entities.createQuery(query).getSingleResult() > 0;
    }

    private void write(RegisteredSystem system, Map<String, String> plain) {
        system.setName(plain.get("name"));
        system.setDescription(plain.get("description"));
        system.setEnvironment(SystemEnvironment.from(plain.get("environment")));
        system.setAnydeskId(plain.get("anydesk_id"));
        system.setAnydeskPassword(secret(plain.get("anydesk_password")));
        system.setRustdeskId(plain.get("rustdesk_id"));
        system.setRustdeskPassword(secret(plain.get("rustdesk_password")));
        system.setRdpHost(plain.get("rdp_host"));
        system.setRdpUsername(plain.get("rdp_username"));
        system.setRdpPassword(secret(plain.get("rdp_password")));
        system.setSshHost(plain.get("ssh_host"));
        system.setSshUsername(plain.get("ssh_username"));
        system.setSshPassword(secret(plain.get("ssh_password")));
        system.setLocalIp(plain.get("local_ip"));
        system.setLocalUsername(plain.get("local_username"));
        system.setLocalPassword(secret(plain.get("local_password")));
        system.setMysqlHost(plain.get("mysql_host"));
        system.setMysqlUsername(plain.get("mysql_username"));
        system.setMysqlPassword(secret(plain.get("mysql_password")));
        system.setDomain(plain.get("domain"));
        system.setDomainUsername(plain.get("domain_username"));
        system.setDomainPassword(secret(plain.get("domain_password")));
        system.setVmHost(plain.get("vm_host"));
        system.setVmUsername(plain.get("vm_username"));
        system.setVmPassword(secret(plain.get("vm_password")));
        system.setNotes(secret(plain.get("notes")));
    }

    private String secret(String value) {
        return value == null ? null : encrypter.encrypt(value);
    }

    private String reveal(String value) {
        return value == null ? null : encrypter.decrypt(value);
    }

    private Map<String, String> readPlain(RegisteredSystem system) {
        Map<String, String> plain = new LinkedHashMap<>();
        plain.put("name", system.getName());
        plain.put("description", system.getDescription());
        plain.put("environment", system.getEnvironment() == null ? null : system.getEnvironment().value());
        plain.put("anydesk_id", system.getAnydeskId());
        plain.put("anydesk_password", reveal(system.getAnydeskPassword()));
        plain.put("rustdesk_id", system.getRustdeskId());
        plain.put("rustdesk_password", reveal(system.getRustdeskPassword()));
        plain.put("rdp_host", system.getRdpHost());
        plain.put("rdp_username", system.getRdpUsername());
        plain.put("rdp_password", reveal(system.getRdpPassword()));
        plain.put("ssh_host", system.getSshHost());
        plain.put("ssh_username", system.getSshUsername());
        plain.put("ssh_password", reveal(system.getSshPassword()));
        plain.put("local_ip", system.getLocalIp());
        plain.put("local_username", system.getLocalUsername());
        plain.put("local_password", reveal(system.getLocalPassword()));
        plain.put("mysql_host", system.getMysqlHost());
        plain.put("mysql_username", system.getMysqlUsername());
        plain.put("mysql_password", reveal(system.getMysqlPassword()));
        plain.put("domain", system.getDomain());
        plain.put("domain_username", system.getDomainUsername());
        plain.put("domain_password", reveal(system.getDomainPassword()));
        plain.put("vm_host", system.getVmHost());
        plain.put("vm_username", system.getVmUsername());
        plain.put("vm_password", reveal(system.getVmPassword()));
        plain.put("notes", reveal(system.getNotes()));
        return plain;
    }

    private Map<String, Object> summary(RegisteredSystem system) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", system.getId());
        row.put("name", system.getName());
        row.put("description", system.getDescription());
        row.put("environment", system.getEnvironment() == null ? null : system.getEnvironment().value());
        row.put("remote_access", remoteAccess(system));
        row.put("local_ip", system.getLocalIp());
        row.put("mysql_host", system.getMysqlHost());
        row.put("domain", system.getDomain());
        row.put("vm_host", system.getVmHost());
        row.put("created_at", TimeFormats.iso(system.getCreatedAt()));
        row.put("updated_at", TimeFormats.iso(system.getUpdatedAt()));
        return row;
    }

    private Map<String, Object> detail(RegisteredSystem system) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", system.getId());
        row.putAll(readPlain(system));
        row.put("environment", system.getEnvironment() == null ? null : system.getEnvironment().value());
        row.put("created_at", TimeFormats.iso(system.getCreatedAt()));
        row.put("updated_at", TimeFormats.iso(system.getUpdatedAt()));
        return row;
    }

    private List<Map<String, Object>> remoteAccess(RegisteredSystem system) {
        String sshTarget = system.getSshHost() != null && system.getSshUsername() != null
                ? system.getSshUsername() + "@" + system.getSshHost()
                : system.getSshHost();
        List<Map<String, Object>> entries = new ArrayList<>();
        addRemote(entries, "anydesk", "AnyDesk", system.getAnydeskId(), system.getAnydeskId(), null);
        addRemote(entries, "rustdesk", "RustDesk", system.getRustdeskId(), system.getRustdeskId(), null);
        addRemote(entries, "rdp", "RDP", system.getRdpHost(), system.getRdpHost(), system.getRdpUsername());
        addRemote(entries, "ssh", "SSH", sshTarget, system.getSshHost(), system.getSshUsername());
        return entries;
    }

    private static void addRemote(List<Map<String, Object>> entries, String type, String label, String value, String address, String username) {
        if (address == null) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("label", label);
        entry.put("value", value);
        entry.put("address", address);
        entry.put("username", username);
        entries.add(entry);
    }

    private List<RegisteredSystem> query(String search, String environment, int page, int perPage) {
        CriteriaBuilder builder = entities.getCriteriaBuilder();
        var query = builder.createQuery(RegisteredSystem.class);
        Root<RegisteredSystem> root = query.from(RegisteredSystem.class);
        query.where(predicates(builder, root, search, environment)).orderBy(builder.asc(root.get("name")), builder.asc(root.get("id")));
        return entities.createQuery(query).setFirstResult((page - 1) * perPage).setMaxResults(perPage).getResultList();
    }

    private long count(String search, String environment) {
        CriteriaBuilder builder = entities.getCriteriaBuilder();
        var query = builder.createQuery(Long.class);
        Root<RegisteredSystem> root = query.from(RegisteredSystem.class);
        query.select(builder.count(root)).where(predicates(builder, root, search, environment));
        return entities.createQuery(query).getSingleResult();
    }

    private Predicate[] predicates(CriteriaBuilder builder, Root<RegisteredSystem> root, String search, String environment) {
        List<Predicate> predicates = new ArrayList<>();
        if (search != null) {
            String like = Texts.likeContains(search.toLowerCase(Locale.ROOT));
            List<Predicate> matches = new ArrayList<>();
            for (String field : SEARCHABLE) {
                matches.add(builder.like(builder.lower(root.get(field)), like, '\\'));
            }
            predicates.add(builder.or(matches.toArray(Predicate[]::new)));
        }
        if (!"all".equals(environment)) {
            predicates.add(builder.equal(root.get("environment"), SystemEnvironment.from(environment)));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private static Map<String, String> blank() {
        Map<String, String> plain = new LinkedHashMap<>();
        for (String field : FIELDS) {
            plain.put(field, null);
        }
        return plain;
    }

    private static String label(String field) {
        return switch (field) {
            case "anydesk_id" -> "AnyDesk ID";
            case "anydesk_password" -> "AnyDesk password";
            case "rustdesk_id" -> "RustDesk ID";
            case "rustdesk_password" -> "RustDesk password";
            case "rdp_host" -> "RDP host";
            case "rdp_username" -> "RDP username";
            case "rdp_password" -> "RDP password";
            case "ssh_host" -> "SSH host";
            case "ssh_username" -> "SSH username";
            case "ssh_password" -> "SSH password";
            case "local_ip" -> "Local IP";
            case "local_username" -> "Local IP username";
            case "local_password" -> "Local IP password";
            case "mysql_host" -> "MySQL IP address";
            case "mysql_username" -> "MySQL username";
            case "mysql_password" -> "MySQL password";
            case "domain" -> "Domain name";
            case "domain_username" -> "Domain username";
            case "domain_password" -> "Domain password";
            case "vm_host" -> "VM host";
            case "vm_username" -> "VM username";
            case "vm_password" -> "VM password";
            default -> field;
        };
    }

    private static String bounded(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw ApiException.errors(Map.of("search", List.of("The search field must not be greater than " + max + " characters.")));
        }
        return trimmed;
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
}
