package ppa.cms.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ppa.cms.persistence.UserAccount;
import ppa.cms.service.AccountService;
import ppa.cms.service.AuditService;
import ppa.cms.service.DashboardService;
import ppa.cms.service.NotificationService;
import ppa.cms.service.RemoteDeskAuthService;
import ppa.cms.service.SystemService;

@RestController
public class WorkflowController {

    private final SystemService systems;
    private final DashboardService dashboard;
    private final NotificationService notifications;
    private final AuditService audit;
    private final RemoteDeskAuthService remoteDeskAuth;

    public WorkflowController(SystemService systems, DashboardService dashboard, NotificationService notifications, AuditService audit, RemoteDeskAuthService remoteDeskAuth) {
        this.systems = systems;
        this.dashboard = dashboard;
        this.notifications = notifications;
        this.audit = audit;
        this.remoteDeskAuth = remoteDeskAuth;
    }

    @GetMapping("/api/dashboard/systems")
    public Map<String, Object> dashboardSystems(Authentication authentication) {
        return dashboard.systems(AccountService.current(authentication));
    }

    @GetMapping("/api/dashboard/sign-ins")
    public Map<String, Object> signIns(Authentication authentication) {
        return dashboard.signIns(AccountService.current(authentication));
    }

    @GetMapping("/api/dashboard/activity")
    public Map<String, Object> activity(Authentication authentication, HttpServletRequest request) {
        return dashboard.activity(AccountService.current(authentication), request);
    }

    @GetMapping("/api/notifications")
    public ResponseEntity<Map<String, Object>> notifications(Authentication authentication, HttpServletRequest request) {
        return notifications.list(AccountService.current(authentication), request);
    }

    @PatchMapping("/api/notifications/{id}/read")
    public Map<String, Object> readNotification(Authentication authentication, @PathVariable String id) {
        return notifications.markRead(AccountService.current(authentication), id);
    }

    @PostMapping("/api/notifications/read-all")
    public Map<String, Object> readAll(Authentication authentication) {
        return notifications.markAllRead(AccountService.current(authentication));
    }

    @GetMapping("/api/audit-trail")
    public Map<String, Object> audit(Authentication authentication, HttpServletRequest request) {
        return audit.list(AccountService.current(authentication), request);
    }

    @PostMapping("/api/audit-trail/refresh")
    public Map<String, Object> refresh(Authentication authentication, HttpServletRequest request) {
        return audit.refresh(AccountService.current(authentication), request);
    }

    @GetMapping("/api/cms-audit-trail")
    public Map<String, Object> cmsAudit(Authentication authentication, HttpServletRequest request) {
        return audit.cmsList(AccountService.current(authentication), request);
    }

    @GetMapping("/api/systems")
    public Map<String, Object> systems(Authentication authentication, HttpServletRequest request) {
        return systems.list(AccountService.current(authentication), request);
    }

    @GetMapping("/api/systems/{id}")
    public Map<String, Object> system(
            Authentication authentication,
            @PathVariable long id,
            @RequestHeader(value = "X-Credential-Grant", required = false) String grant
    ) {
        UserAccount actor = AccountService.current(authentication);
        remoteDeskAuth.consume(actor, id, Map.of("grant", grant == null ? "" : grant, "type", "details"));
        return systems.show(actor, id);
    }

    @PostMapping("/api/systems")
    public ResponseEntity<Map<String, Object>> createSystem(Authentication authentication, @RequestBody Map<String, Object> body) {
        Map<String, Object> created = systems.store(AccountService.current(authentication), body);
        return ResponseEntity.status(201).body(created);
    }

    @PatchMapping("/api/systems/{id}")
    public Map<String, Object> updateSystem(Authentication authentication, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return systems.update(AccountService.current(authentication), id, body);
    }

    @DeleteMapping("/api/systems/{id}")
    public Map<String, Object> deleteSystem(Authentication authentication, @PathVariable long id) {
        return systems.destroy(AccountService.current(authentication), id);
    }

    @PostMapping("/api/systems/{id}/remote-desks")
    public Map<String, Object> remoteDesk(Authentication authentication, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return systems.openRemoteDesk(AccountService.current(authentication), id, body);
    }

    @PostMapping("/api/systems/{id}/credential-access")
    public Map<String, Object> credentialAccess(Authentication authentication, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return systems.recordCredentialAccess(AccountService.current(authentication), id, body);
    }

    @PostMapping("/api/remote-desk-auth/pin")
    public Map<String, Object> changePin(Authentication authentication, @RequestBody Map<String, Object> body) {
        return remoteDeskAuth.changePin(AccountService.current(authentication), body);
    }

    @PostMapping("/api/credential-confirm")
    public Map<String, Object> confirmCredentials(Authentication authentication, @RequestBody Map<String, Object> body) {
        UserAccount actor = AccountService.current(authentication);
        long systemId = body.get("system_id") instanceof Number number ? number.longValue() : -1;
        systems.require(systemId);
        return remoteDeskAuth.confirmDetails(actor, systemId, body);
    }

    @GetMapping("/api/remote-desk-auth")
    public Map<String, Object> remoteDeskAuthStatus(Authentication authentication) {
        return remoteDeskAuth.status(AccountService.current(authentication));
    }

    @PostMapping("/api/remote-desk-auth/challenge")
    public Map<String, Object> remoteDeskAuthChallenge(Authentication authentication, HttpServletRequest request) {
        return remoteDeskAuth.challenge(AccountService.current(authentication), request);
    }

    @PostMapping("/api/remote-desk-auth")
    public Map<String, Object> remoteDeskAuth(Authentication authentication, HttpServletRequest request, @RequestBody Map<String, Object> body) {
        UserAccount actor = AccountService.current(authentication);
        long systemId = body.get("system_id") instanceof Number number ? number.longValue() : -1;
        String type = body.get("type") == null ? null : String.valueOf(body.get("type"));
        systems.remoteDeskLabel(actor, systemId, type);
        return remoteDeskAuth.authorize(actor, systemId, type, body, request);
    }
}
