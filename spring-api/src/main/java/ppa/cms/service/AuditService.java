package ppa.cms.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ppa.cms.config.CmsProperties;
import ppa.cms.domain.AuditSource;
import ppa.cms.domain.CmsAuditAction;
import ppa.cms.domain.CmsAuditModule;
import ppa.cms.persistence.AuditRecord;
import ppa.cms.persistence.AuditRecordRepository;
import ppa.cms.persistence.AuditSourceStatus;
import ppa.cms.persistence.AuditSourceStatusRepository;
import ppa.cms.persistence.CmsAuditEvent;
import ppa.cms.persistence.UserAccount;
import ppa.cms.security.Access;
import ppa.cms.support.JsonCodec;
import ppa.cms.support.PageSlice;
import ppa.cms.support.Texts;
import ppa.cms.support.TimeFormats;
import ppa.cms.web.ApiException;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final List<String> RECORD_SEARCH = List.of("actorName", "userLevel", "fieldOffice", "module", "action");
    private static final List<String> CMS_SEARCH = List.of("actorName", "actorUsername", "subjectLabel", "summary");

    private final AuditRecordRepository records;
    private final AuditSourceStatusRepository statuses;
    private final EntityManager entities;
    private final CmsProperties properties;

    public AuditService(
            AuditRecordRepository records,
            AuditSourceStatusRepository statuses,
            EntityManager entities,
            CmsProperties properties
    ) {
        this.records = records;
        this.statuses = statuses;
        this.entities = entities;
        this.properties = properties;
    }

    public Map<String, Object> list(UserAccount actor, HttpServletRequest request) {
        Access.requireViewAudit(actor);
        return listing(request);
    }

    @Transactional
    public Map<String, Object> refresh(UserAccount actor, HttpServletRequest request) {
        Access.requireViewAudit(actor);
        filters(request);
        sync();
        Map<String, Object> payload = listing(request);
        payload.put("message", refreshMessage(payload.get("sources")));
        return payload;
    }

    public Map<String, Object> cmsList(UserAccount actor, HttpServletRequest request) {
        Access.requireViewAudit(actor);
        String search = bounded(request.getParameter("search"));
        String module = request.getParameter("module");
        if (module == null || module.isBlank()) {
            module = "all";
        }
        if (!module.equals("all") && !List.of("session", "credentials", "users").contains(module)) {
            throw ApiException.errors(Map.of("module", List.of("The selected module is invalid.")));
        }
        ZoneId zone = ZoneId.of(properties.getDisplayTimezone());
        Instant from = start(request.getParameter("from"), zone);
        Instant to = end(request.getParameter("to"), request.getParameter("from"), zone);
        int page = page(request);
        int perPage = Math.max(1, properties.getAudit().getCmsPerPage());
        long total = countCms(search, module, from, to);
        CriteriaBuilder builder = entities.getCriteriaBuilder();
        var query = builder.createQuery(CmsAuditEvent.class);
        Root<CmsAuditEvent> root = query.from(CmsAuditEvent.class);
        query.where(cmsPredicates(builder, root, search, module, from, to))
                .orderBy(builder.desc(root.get("occurredAt")), builder.desc(root.get("id")));
        List<CmsAuditEvent> items = entities.createQuery(query).setFirstResult((page - 1) * perPage).setMaxResults(perPage).getResultList();
        PageSlice<CmsAuditEvent> slice = new PageSlice<>(items, page, PageSlice.lastPage(total, perPage), perPage, total);
        Map<String, Object> meta = AccountService.meta(slice);
        meta.put("total_recorded", entities.createQuery("select count(e) from CmsAuditEvent e", Long.class).getSingleResult());
        meta.put("timezone", properties.getDisplayTimezone());
        meta.put("generated_at", TimeFormats.iso(Instant.now()));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("all", countCms(search, "all", from, to));
        for (CmsAuditModule value : CmsAuditModule.values()) {
            counts.put(value.value(), countCms(search, value.value(), from, to));
        }
        return Map.of("data", items.stream().map(this::cmsResource).toList(), "meta", meta, "counts", counts);
    }

    @Transactional
    public List<Map<String, Object>> sync() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (AuditSource source : AuditSource.values()) {
            Snapshot snapshot = read(source);
            AuditSourceStatus status = statuses.findBySource(source.value()).orElseGet(() -> {
                AuditSourceStatus created = new AuditSourceStatus();
                created.setSource(source.value());
                return created;
            });
            int saved = 0;
            if (snapshot.available()) {
                try {
                    saved = store(source, snapshot.records());
                    status.setAvailable(true);
                    status.setLastError(null);
                    status.setLastSyncedAt(Instant.now());
                } catch (RuntimeException exception) {
                    log.warn("Audit source {} could not be saved.", source.value(), exception);
                    status.setAvailable(false);
                    status.setLastError(source.label() + " could not be saved. Records already saved are unchanged.");
                }
            } else {
                status.setAvailable(false);
                status.setLastError(snapshot.error());
            }
            statuses.save(status);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", source.value());
            result.put("label", source.label());
            result.put("available", status.isAvailable());
            result.put("saved", saved);
            result.put("last_error", status.getLastError());
            result.put("last_synced_at", TimeFormats.iso(status.getLastSyncedAt()));
            results.add(result);
        }
        return results;
    }

    private Map<String, Object> listing(HttpServletRequest request) {
        String search = bounded(request.getParameter("search"));
        String source = request.getParameter("source");
        if (source == null || source.isBlank()) {
            source = "all";
        }
        if (!source.equals("all") && !List.of("uams", "cmis", "ppis").contains(source)) {
            throw ApiException.errors(Map.of("source", List.of("The selected source is invalid.")));
        }
        ZoneId zone = ZoneId.of(properties.getDisplayTimezone());
        Instant from = start(request.getParameter("from"), zone);
        Instant to = end(request.getParameter("to"), request.getParameter("from"), zone);
        int page = page(request);
        int perPage = Math.max(1, properties.getAudit().getPerPage());
        long total = countRecords(search, source, from, to);
        CriteriaBuilder builder = entities.getCriteriaBuilder();
        var query = builder.createQuery(AuditRecord.class);
        Root<AuditRecord> root = query.from(AuditRecord.class);
        query.where(recordPredicates(builder, root, search, source, from, to)).orderBy(
                builder.asc(builder.selectCase().when(builder.isNull(root.get("occurredAt")), 1).otherwise(0)),
                builder.desc(root.get("occurredAt")),
                builder.desc(root.get("externalId")),
                builder.desc(root.get("id"))
        );
        List<AuditRecord> items = entities.createQuery(query).setFirstResult((page - 1) * perPage).setMaxResults(perPage).getResultList();
        PageSlice<AuditRecord> slice = new PageSlice<>(items, page, PageSlice.lastPage(total, perPage), perPage, total);
        Map<String, Object> meta = AccountService.meta(slice);
        meta.put("total_saved", records.count());
        meta.put("timezone", properties.getDisplayTimezone());
        meta.put("reads", "saved_records");
        meta.put("generated_at", TimeFormats.iso(Instant.now()));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("all", countRecords(search, "all", from, to));
        for (AuditSource value : AuditSource.values()) {
            counts.put(value.value(), countRecords(search, value.value(), from, to));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", items.stream().map(this::recordResource).toList());
        body.put("meta", meta);
        body.put("counts", counts);
        body.put("sources", sourceSummaries());
        return body;
    }

    private int store(AuditSource source, List<CopiedRecord> rows) {
        int saved = 0;
        Instant now = Instant.now();
        for (CopiedRecord row : rows) {
            if (row.externalId() < 1) {
                continue;
            }
            AuditRecord record = records.findBySourceAndExternalId(source.value(), row.externalId()).orElseGet(AuditRecord::new);
            if (record.getId() == null) {
                record.setSource(source.value());
                record.setExternalId(row.externalId());
            }
            record.setOccurredAt(parseOccurred(row.occurredAt()));
            record.setActorName(row.actorName());
            record.setUserLevel(row.userLevel());
            record.setFieldOffice(row.fieldOffice());
            record.setModule(row.module());
            record.setAction(row.action());
            record.setUpdatedAt(now);
            records.save(record);
            saved++;
        }
        return saved;
    }

    private Snapshot read(AuditSource source) {
        CmsProperties.Source settings = switch (source) {
            case UAMS -> properties.getAudit().getUams();
            case CMIS -> properties.getAudit().getCmis();
            case PPIS -> properties.getAudit().getPpis();
        };
        String database = settings.getDatabase() == null ? "" : settings.getDatabase().trim();
        if (database.isEmpty()) {
            return Snapshot.unavailable(source.label() + " is not configured for audit reading.");
        }
        DriverManagerDataSource remote = new DriverManagerDataSource();
        remote.setDriverClassName("com.mysql.cj.jdbc.Driver");
        remote.setUrl("jdbc:mysql://" + settings.getHost() + ":" + settings.getPort() + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        remote.setUsername(settings.getUsername());
        remote.setPassword(settings.getPassword() == null ? "" : settings.getPassword());
        try (Connection connection = remote.getConnection()) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) {
                statement.execute("SET SESSION TRANSACTION READ ONLY");
                statement.execute("SET SESSION MAX_EXECUTION_TIME=" + Math.max(1, properties.getAudit().getStatementTimeoutMs()));
            }
            if (!hasTable(connection, "audit_trail")) {
                return Snapshot.unavailable(source.label() + " has no audit trail to read.");
            }
            return new Snapshot(true, null, records(connection));
        } catch (Exception exception) {
            log.warn("Audit trail could not be read from {}.", source.value(), exception);
            return Snapshot.unavailable(source.label() + " could not be read. Records already saved are unchanged.");
        }
    }

    private List<CopiedRecord> records(Connection connection) throws Exception {
        boolean hasUsers = hasTable(connection, "USERS");
        List<String> userColumns = hasUsers ? columns(connection, "USERS") : List.of();
        boolean hasLevels = hasTable(connection, "USER_LEVEL");
        List<String> levelColumns = hasLevels ? columns(connection, "USER_LEVEL") : List.of();
        boolean joinedLevel = hasUsers && hasLevels && hasColumn(levelColumns, "USER_LEVEL_NAME") && hasColumn(userColumns, "USER_LEVEL_ID");
        StringBuilder sql = new StringBuilder("select a.audit_trail_id as external_id, a.created_date as occurred_at, a.module, a.action");
        for (String column : List.of("USER_FULLNAME", "USER_FNAME", "USER_MNAME", "USER_LNAME", "USER_EMAIL", "FIELD_OFFICE")) {
            if (hasUsers && hasColumn(userColumns, column)) {
                sql.append(", u.").append(column);
            }
        }
        if (joinedLevel) {
            sql.append(", l.USER_LEVEL_NAME");
        }
        sql.append(" from audit_trail a");
        if (hasUsers) {
            sql.append(" left join USERS u on u.USER_ID = a.created_by");
        }
        if (joinedLevel) {
            sql.append(" left join USER_LEVEL l on l.USER_LEVEL_ID = u.USER_LEVEL_ID");
        }
        sql.append(" order by a.audit_trail_id desc limit ").append(Math.max(1, properties.getAudit().getSyncLimit()));
        List<CopiedRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString()); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long externalId = rows.getLong("external_id");
                if (externalId < 1 || records.stream().anyMatch(record -> record.externalId() == externalId)) {
                    continue;
                }
                records.add(new CopiedRecord(
                        externalId,
                        text(rows, "occurred_at", 32),
                        actorName(rows),
                        text(rows, "USER_LEVEL_NAME", 255),
                        text(rows, "FIELD_OFFICE", 255),
                        text(rows, "module", 255),
                        text(rows, "action", 2000)
                ));
            }
        }
        return records;
    }

    private List<Map<String, Object>> sourceSummaries() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AuditSource source : AuditSource.values()) {
            Optional<AuditSourceStatus> status = statuses.findBySource(source.value());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", source.value());
            row.put("label", source.label());
            row.put("available", status.map(AuditSourceStatus::isAvailable).orElse(null));
            row.put("last_synced_at", status.map(item -> TimeFormats.iso(item.getLastSyncedAt())).orElse(null));
            row.put("last_error", status.map(AuditSourceStatus::getLastError).orElse(null));
            row.put("saved", records.countBySource(source.value()));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> recordResource(AuditRecord record) {
        AuditSource source = AuditSource.valueOf(record.getSource().toUpperCase(Locale.ROOT));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", record.getId());
        row.put("source", source.value());
        row.put("source_label", source.label());
        row.put("external_id", record.getExternalId());
        row.put("occurred_at", TimeFormats.iso(record.getOccurredAt()));
        row.put("actor_name", record.getActorName());
        row.put("user_level", record.getUserLevel());
        row.put("field_office", record.getFieldOffice());
        row.put("module", record.getModule());
        row.put("action", record.getAction());
        return row;
    }

    private Map<String, Object> cmsResource(CmsAuditEvent event) {
        Map<String, Object> details = event.getDetails() == null ? Map.of() : JsonCodec.readObject(event.getDetails());
        CmsAuditAction action = null;
        for (CmsAuditAction candidate : CmsAuditAction.values()) {
            if (candidate.value().equals(event.getAction())) {
                action = candidate;
            }
        }
        CmsAuditModule module = null;
        for (CmsAuditModule candidate : CmsAuditModule.values()) {
            if (candidate.value().equals(event.getModule())) {
                module = candidate;
            }
        }
        Object remote = details.get("remote_desk");
        Object changed = details.get("changed");
        List<String> fields = new ArrayList<>();
        if (changed instanceof List<?> list) {
            for (Object field : list) {
                if (field instanceof String text && !text.isBlank()) {
                    fields.add(text);
                }
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", event.getId());
        row.put("occurred_at", TimeFormats.iso(event.getOccurredAt()));
        row.put("actor_name", event.getActorName());
        row.put("actor_username", event.getActorUsername());
        row.put("module", event.getModule());
        row.put("module_label", module == null ? event.getModule() : module.label());
        row.put("action", event.getAction());
        row.put("action_label", action == null ? event.getAction() : action.label());
        row.put("subject_label", event.getSubjectLabel());
        row.put("summary", event.getSummary());
        row.put("remote_desk", remote instanceof String text && !text.isBlank() ? text : null);
        row.put("changed", fields);
        return row;
    }

    private Predicate[] recordPredicates(CriteriaBuilder builder, Root<AuditRecord> root, String search, String source, Instant from, Instant to) {
        List<Predicate> predicates = searchPredicates(builder, root, search, RECORD_SEARCH);
        if (!"all".equals(source)) {
            predicates.add(builder.equal(root.get("source"), source));
        }
        if (from != null) {
            predicates.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), from));
        }
        if (to != null) {
            predicates.add(builder.lessThan(root.get("occurredAt"), to));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private Predicate[] cmsPredicates(CriteriaBuilder builder, Root<CmsAuditEvent> root, String search, String module, Instant from, Instant to) {
        List<Predicate> predicates = searchPredicates(builder, root, search, CMS_SEARCH);
        if (!"all".equals(module)) {
            predicates.add(builder.equal(root.get("module"), module));
        }
        if (from != null) {
            predicates.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), from));
        }
        if (to != null) {
            predicates.add(builder.lessThan(root.get("occurredAt"), to));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private List<Predicate> searchPredicates(CriteriaBuilder builder, Root<?> root, String search, List<String> fields) {
        List<Predicate> predicates = new ArrayList<>();
        if (search != null) {
            String like = Texts.likeContains(search.toLowerCase(Locale.ROOT));
            List<Predicate> matches = new ArrayList<>();
            for (String field : fields) {
                matches.add(builder.like(builder.lower(root.get(field)), like, '\\'));
            }
            predicates.add(builder.or(matches.toArray(Predicate[]::new)));
        }
        return predicates;
    }

    private long countRecords(String search, String source, Instant from, Instant to) {
        CriteriaBuilder builder = entities.getCriteriaBuilder();
        var query = builder.createQuery(Long.class);
        Root<AuditRecord> root = query.from(AuditRecord.class);
        query.select(builder.count(root)).where(recordPredicates(builder, root, search, source, from, to));
        return entities.createQuery(query).getSingleResult();
    }

    private long countCms(String search, String module, Instant from, Instant to) {
        CriteriaBuilder builder = entities.getCriteriaBuilder();
        var query = builder.createQuery(Long.class);
        Root<CmsAuditEvent> root = query.from(CmsAuditEvent.class);
        query.select(builder.count(root)).where(cmsPredicates(builder, root, search, module, from, to));
        return entities.createQuery(query).getSingleResult();
    }

    private Instant parseOccurred(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // The source date has no offset, so it is read in the display timezone.
        }
        ZoneId zone = ZoneId.of(properties.getDisplayTimezone());
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ISO_LOCAL_DATE
        )) {
            try {
                if (formatter == DateTimeFormatter.ISO_LOCAL_DATE) {
                    return LocalDate.parse(value, formatter).atStartOfDay(zone).toInstant();
                }
                return LocalDateTime.parse(value, formatter).atZone(zone).toInstant();
            } catch (DateTimeParseException ignored) {
                // Try the next pattern.
            }
        }
        return null;
    }

    private void filters(HttpServletRequest request) {
        bounded(request.getParameter("search"));
        String source = request.getParameter("source");
        if (source != null && !source.isBlank() && !source.equals("all") && !List.of("uams", "cmis", "ppis").contains(source)) {
            throw ApiException.errors(Map.of("source", List.of("The selected source is invalid.")));
        }
        ZoneId zone = ZoneId.of(properties.getDisplayTimezone());
        start(request.getParameter("from"), zone);
        end(request.getParameter("to"), request.getParameter("from"), zone);
        page(request);
    }

    @SuppressWarnings("unchecked")
    private String refreshMessage(Object sources) {
        List<String> problems = new ArrayList<>();
        if (sources instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object error = map.get("last_error");
                    if (error instanceof String text && !text.isBlank()) {
                        problems.add(text);
                    }
                }
            }
        }
        if (problems.isEmpty()) {
            return "Audit records refreshed.";
        }
        return "Audit records refreshed with problems: " + String.join(" ", problems);
    }

    private static boolean hasTable(Connection connection, String name) throws Exception {
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                if (name.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> columns(Connection connection, String table) throws Exception {
        List<String> names = new ArrayList<>();
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "%", "%")) {
            while (columns.next()) {
                if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))) {
                    names.add(columns.getString("COLUMN_NAME"));
                }
            }
        }
        return names;
    }

    private static boolean hasColumn(List<String> columns, String name) {
        return columns.stream().anyMatch(column -> column.equalsIgnoreCase(name));
    }

    private static String actorName(ResultSet row) throws Exception {
        String full = text(row, "USER_FULLNAME", 255);
        if (full != null) {
            return full;
        }
        List<String> parts = new ArrayList<>();
        for (String column : List.of("USER_FNAME", "USER_MNAME", "USER_LNAME")) {
            String part = text(row, column, 255);
            if (part != null) {
                parts.add(part);
            }
        }
        if (!parts.isEmpty()) {
            return limit(String.join(" ", parts), 255);
        }
        return text(row, "USER_EMAIL", 255);
    }

    private static String text(ResultSet row, String column, int max) throws Exception {
        try {
            String value = row.getString(column);
            if (value == null) {
                return null;
            }
            return limit(value.trim(), max);
        } catch (Exception exception) {
            return null;
        }
    }

    private static String limit(String value, int max) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static Instant start(String value, ZoneId zone) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException exception) {
            throw ApiException.errors(Map.of("from", List.of("The from field must be a valid date.")));
        }
    }

    private static Instant end(String value, String from, ZoneId zone) {
        if (value == null || value.isBlank()) {
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw ApiException.errors(Map.of("to", List.of("The to field must be a valid date.")));
        }
        if (from != null && !from.isBlank()) {
            try {
                if (date.isBefore(LocalDate.parse(from))) {
                    throw ApiException.errors(Map.of("to", List.of("The to field must be a date after or equal to from.")));
                }
            } catch (DateTimeParseException ignored) {
                // The from field already reported its own error when it was parsed.
            }
        }
        return date.plusDays(1).atStartOfDay(zone).toInstant();
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 100) {
            throw ApiException.errors(Map.of("search", List.of("The search field must not be greater than 100 characters.")));
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

    private record CopiedRecord(long externalId, String occurredAt, String actorName, String userLevel, String fieldOffice, String module, String action) {
    }

    private record Snapshot(boolean available, String error, List<CopiedRecord> records) {
        static Snapshot unavailable(String error) {
            return new Snapshot(false, error, List.of());
        }
    }
}
