package ppa.cms.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.servlet.http.HttpServletRequest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ppa.cms.config.CmsProperties;
import ppa.cms.domain.CmsAuditAction;
import ppa.cms.domain.SystemEnvironment;
import ppa.cms.persistence.CmsAuditEvent;
import ppa.cms.persistence.RegisteredSystem;
import ppa.cms.persistence.RegisteredSystemRepository;
import ppa.cms.persistence.StatusCheck;
import ppa.cms.persistence.UserAccount;
import ppa.cms.security.Access;
import ppa.cms.support.Addresses;
import ppa.cms.support.TimeFormats;
import ppa.cms.web.ApiException;

@Service
public class DashboardService {

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final Map<String, String> RANGES = Map.of(
            "current_week", "This week",
            "last_week", "Last week",
            "last_30_days", "Last 30 days"
    );

    private final RegisteredSystemRepository systems;
    private final AccountService accounts;
    private final EntityManager entities;
    private final CmsProperties properties;

    public DashboardService(RegisteredSystemRepository systems, AccountService accounts, EntityManager entities, CmsProperties properties) {
        this.systems = systems;
        this.accounts = accounts;
        this.entities = entities;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> systems(UserAccount actor) {
        Access.requireManageSystems(actor);
        boolean canSeePrivate = Access.manageSystems(actor);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RegisteredSystem system : systems.findByEnvironmentOrderByNameAscIdAsc(SystemEnvironment.PRODUCTION)) {
            rows.add(systemRow(system, canSeePrivate));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("total", rows.size());
        meta.put("check_interval_minutes", properties.getSystemStatus().getIntervalMinutes());
        meta.put("stale_after_minutes", properties.getSystemStatus().getStaleAfterMinutes());
        meta.put("timezone", properties.getDisplayTimezone());
        meta.put("generated_at", TimeFormats.iso(Instant.now()));
        return Map.of("data", rows, "meta", meta);
    }

    public Map<String, Object> signIns(UserAccount actor) {
        Access.requireSignIns(actor);
        int limit = accounts.signInLimit();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("shows", "recent_sign_ins");
        meta.put("limit", limit);
        meta.put("timezone", properties.getDisplayTimezone());
        meta.put("generated_at", TimeFormats.iso(Instant.now()));
        return Map.of("data", accounts.recentSignIns(), "meta", meta);
    }

    public Map<String, Object> activity(UserAccount actor, HttpServletRequest request) {
        Access.requireViewAudit(actor);
        String series = request.getParameter("series");
        String range = request.getParameter("range");
        if (range == null || range.isBlank()) {
            range = "current_week";
        }
        List<CmsAuditAction> actions = switch (series == null ? "" : series) {
            case "remote_desks" -> List.of(CmsAuditAction.REMOTE_DESK_OPENED);
            case "credential_changes" -> List.of(CmsAuditAction.CREDENTIALS_REGISTERED, CmsAuditAction.CREDENTIALS_UPDATED, CmsAuditAction.CREDENTIALS_REMOVED);
            case "account_changes" -> List.of(CmsAuditAction.USER_REGISTERED, CmsAuditAction.USER_UPDATED, CmsAuditAction.USER_DISABLED, CmsAuditAction.USER_REMOVED);
            default -> null;
        };
        if (actions == null) {
            throw ApiException.errors(Map.of("series", List.of(series == null || series.isBlank() ? "The series field is required." : "The selected series is invalid.")));
        }
        if (!RANGES.containsKey(range)) {
            throw ApiException.errors(Map.of("range", List.of("The selected range is invalid.")));
        }
        ZoneId zone = ZoneId.of(properties.getDisplayTimezone());
        LocalDate today = LocalDate.now(zone);
        LocalDate startOfWeek = today.with(DayOfWeek.MONDAY);
        LocalDate start = switch (range) {
            case "last_week" -> startOfWeek.minusWeeks(1);
            case "last_30_days" -> today.minusDays(29);
            default -> startOfWeek;
        };
        LocalDate end = "last_week".equals(range) ? start.plusDays(6) : today;
        Instant from = start.atStartOfDay(zone).toInstant();
        Instant until = end.plusDays(1).atStartOfDay(zone).toInstant();
        CriteriaBuilder builder = entities.getCriteriaBuilder();
        var query = builder.createQuery(CmsAuditEvent.class);
        Root<CmsAuditEvent> root = query.from(CmsAuditEvent.class);
        List<Predicate> actionPredicates = new ArrayList<>();
        for (CmsAuditAction action : actions) {
            actionPredicates.add(builder.equal(root.get("action"), action.value()));
        }
        query.where(
                builder.or(actionPredicates.toArray(Predicate[]::new)),
                builder.greaterThanOrEqualTo(root.get("occurredAt"), from),
                builder.lessThan(root.get("occurredAt"), until)
        );
        Map<LocalDate, Integer> counts = new LinkedHashMap<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            counts.put(day, 0);
        }
        for (CmsAuditEvent event : entities.createQuery(query).getResultList()) {
            LocalDate day = event.getOccurredAt().atZone(zone).toLocalDate();
            if (counts.containsKey(day)) {
                counts.put(day, counts.get(day) + 1);
            }
        }
        List<Map<String, Object>> points = new ArrayList<>();
        int total = 0;
        for (Map.Entry<LocalDate, Integer> entry : counts.entrySet()) {
            total += entry.getValue();
            points.add(Map.of(
                    "date", entry.getKey().toString(),
                    "label", DAY_LABEL.format(entry.getKey()),
                    "count", entry.getValue()
            ));
        }
        String seriesLabel = switch (series) {
            case "remote_desks" -> "Remote desks opened";
            case "credential_changes" -> "Credential changes";
            default -> "Account changes";
        };
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("series", series);
        meta.put("series_label", seriesLabel);
        meta.put("range", range);
        meta.put("range_label", RANGES.get(range));
        meta.put("from", start.toString());
        meta.put("to", end.toString());
        meta.put("total", total);
        meta.put("timezone", properties.getDisplayTimezone());
        meta.put("generated_at", TimeFormats.iso(Instant.now()));
        return Map.of("data", points, "meta", meta);
    }

    private Map<String, Object> systemRow(RegisteredSystem system, boolean canSeePrivate) {
        String domain = Addresses.hostPort(system.getDomain());
        String domainHost = domain == null ? null : domain.split(":", 2)[0];
        boolean domainHidden = domain != null && !canSeePrivate && Addresses.nonPublicIp(domainHost);
        String ip = Addresses.hostOnly(system.getLocalIp());
        StatusCheck check = system.getStatusCheck();
        Map<String, Object> status = new LinkedHashMap<>();
        if (check == null) {
            status.put("state", "unknown");
            status.put("reason", domain == null ? "No domain name is recorded for this system." : "Not checked yet.");
            status.put("http_status", null);
            status.put("response_time_ms", null);
            status.put("checked_at", null);
            status.put("next_check_at", null);
            status.put("is_stale", false);
        } else {
            Instant staleAt = check.getNextCheckAt().plusSeconds(properties.getSystemStatus().getStaleAfterMinutes() * 60L);
            status.put("state", check.getStatus().value());
            status.put("reason", check.getReason());
            status.put("http_status", check.getHttpStatus());
            status.put("response_time_ms", check.getResponseTimeMs());
            status.put("checked_at", TimeFormats.iso(check.getCheckedAt()));
            status.put("next_check_at", TimeFormats.iso(check.getNextCheckAt()));
            status.put("is_stale", staleAt.isBefore(Instant.now()));
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", system.getId());
        row.put("name", system.getName());
        row.put("domain", domainHidden ? null : domain);
        row.put("domain_hidden", domainHidden);
        row.put("ip_address", canSeePrivate ? ip : null);
        row.put("ip_address_hidden", !canSeePrivate && ip != null);
        row.put("status", status);
        return row;
    }
}
