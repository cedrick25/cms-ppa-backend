package ppa.cms.service;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ppa.cms.config.CmsProperties;
import ppa.cms.domain.Reachability;
import ppa.cms.persistence.StoredNotification;
import ppa.cms.persistence.StoredNotificationRepository;
import ppa.cms.persistence.UserAccount;
import ppa.cms.persistence.UserAccountRepository;
import ppa.cms.support.JsonCodec;
import ppa.cms.support.TimeFormats;
import ppa.cms.web.ApiException;

@Service
public class NotificationService {

    private final StoredNotificationRepository notifications;
    private final UserAccountRepository users;
    private final CmsProperties properties;

    public NotificationService(StoredNotificationRepository notifications, UserAccountRepository users, CmsProperties properties) {
        this.notifications = notifications;
        this.users = users;
        this.properties = properties;
    }

    public ResponseEntity<Map<String, Object>> list(UserAccount user, HttpServletRequest request) {
        int limit = Math.max(1, properties.getNotifications().getDashboardLimit());
        List<StoredNotification> all = notifications.findByTypeAndNotifiableTypeAndNotifiableIdOrderByCreatedAtDesc(
                StoredNotification.TYPE, StoredNotification.NOTIFIABLE, user.getId(), PageRequest.of(0, 10_000));
        long unread = all.stream().filter(item -> item.getReadAt() == null).count();
        Instant latestCreated = all.stream().map(StoredNotification::getCreatedAt).max(Instant::compareTo).orElse(null);
        Instant latestRead = all.stream().map(StoredNotification::getReadAt).filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
        String fingerprint = user.getId() + "|" + limit + "|" + properties.getDisplayTimezone() + "|" + all.size() + "|" + unread + "|" + latestCreated + "|" + latestRead;
        String hash = sha256(fingerprint);
        String quoted = "\"" + hash + "\"";
        String incoming = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        if (incoming != null && incoming.replace("W/", "").replace("\"", "").contains(hash)) {
            return ResponseEntity.status(304).eTag(quoted).build();
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (StoredNotification notification : all.stream().limit(limit).toList()) {
            data.add(resource(notification));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("unread_count", unread);
        meta.put("limit", limit);
        meta.put("timezone", properties.getDisplayTimezone());
        meta.put("generated_at", TimeFormats.iso(Instant.now()));
        Map<String, Object> body = Map.of("data", data, "meta", meta);
        return ResponseEntity.ok().eTag(quoted).body(body);
    }

    @Transactional
    public Map<String, Object> markRead(UserAccount user, String id) {
        StoredNotification notification = notifications.findById(id)
                .filter(item -> item.getNotifiableId().equals(user.getId()) && StoredNotification.TYPE.equals(item.getType()))
                .orElseThrow(() -> ApiException.message(404, "Not found."));
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
            notifications.save(notification);
        }
        long unread = notifications.findByTypeAndNotifiableTypeAndNotifiableIdAndReadAtIsNull(
                StoredNotification.TYPE, StoredNotification.NOTIFIABLE, user.getId()).size();
        return Map.of("notification", resource(notification), "meta", Map.of("unread_count", unread));
    }

    @Transactional
    public Map<String, Object> markAllRead(UserAccount user) {
        List<StoredNotification> unread = notifications.findByTypeAndNotifiableTypeAndNotifiableIdAndReadAtIsNull(
                StoredNotification.TYPE, StoredNotification.NOTIFIABLE, user.getId());
        Instant now = Instant.now();
        for (StoredNotification notification : unread) {
            notification.setReadAt(now);
        }
        notifications.saveAll(unread);
        String message = unread.size() == 1 ? "1 notification marked as read." : unread.size() + " notifications marked as read.";
        return Map.of("message", message, "meta", Map.of("unread_count", 0));
    }

    @Transactional
    public void notifyStatus(String systemName, Reachability status, Reachability previousStatus, int previousFailures, int failures, String reason) {
        int threshold = Math.max(1, properties.getNotifications().getDownAfterFailures());
        String event = null;
        int failedChecks = failures;
        if (previousStatus == null) {
            return;
        }
        if (status == Reachability.DOWN && failures == threshold) {
            event = "down";
        } else if (status == Reachability.UP && previousStatus == Reachability.DOWN && previousFailures >= threshold) {
            event = "recovered";
            failedChecks = previousFailures;
        }
        if (event == null) {
            return;
        }
        String failed = failedChecks == 1 ? "1 failed check" : failedChecks + " failed checks";
        String title;
        String message;
        if ("down".equals(event)) {
            title = systemName + " is down";
            message = (failedChecks == 1 ? "The latest check failed." : "Failed " + failedChecks + " checks in a row.") + " " + reason;
        } else {
            title = systemName + " is back up";
            message = "Answering again after " + failed + ". " + reason;
        }
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("page", "dashboard");
        link.put("search", null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "system_status");
        data.put("event", event);
        data.put("title", title);
        data.put("message", message);
        data.put("changed", List.of());
        data.put("performed_by", null);
        data.put("link", link);
        String json = JsonCodec.write(data);
        Instant now = Instant.now();
        for (UserAccount user : users.findByDisabledAtIsNull()) {
            StoredNotification notification = new StoredNotification();
            notification.setId(UUID.randomUUID().toString());
            notification.setType(StoredNotification.TYPE);
            notification.setNotifiableType(StoredNotification.NOTIFIABLE);
            notification.setNotifiableId(user.getId());
            notification.setData(json);
            notification.setCreatedAt(now);
            notifications.save(notification);
        }
    }

    @Transactional
    public void prune() {
        Instant readBefore = Instant.now().minus(Duration.ofDays(properties.getNotifications().getReadRetentionDays()));
        Instant createdBefore = Instant.now().minus(Duration.ofDays(properties.getNotifications().getRetentionDays()));
        notifications.deleteByReadAtBeforeOrCreatedAtBefore(readBefore, createdBefore);
    }

    private Map<String, Object> resource(StoredNotification notification) {
        Map<String, Object> data = JsonCodec.readObject(notification.getData());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", notification.getId());
        row.put("category", data.getOrDefault("category", "general"));
        row.put("event", data.get("event"));
        row.put("title", data.getOrDefault("title", "Notification"));
        row.put("message", data.getOrDefault("message", ""));
        row.put("changed", data.getOrDefault("changed", List.of()));
        row.put("performed_by", data.get("performed_by"));
        row.put("link", data.get("link"));
        row.put("is_read", notification.getReadAt() != null);
        row.put("read_at", TimeFormats.iso(notification.getReadAt()));
        row.put("created_at", TimeFormats.iso(notification.getCreatedAt()));
        return row;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte piece : digest) {
                hex.append(String.format("%02x", piece));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
