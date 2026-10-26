package ppa.cms.schedule;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ppa.cms.service.AccountService;
import ppa.cms.service.AuditService;
import ppa.cms.service.NotificationService;
import ppa.cms.service.StatusMonitor;

@Component
public class ScheduledJobs {

    private final StatusMonitor monitor;
    private final AuditService audit;
    private final AccountService accounts;
    private final NotificationService notifications;
    private final ReentrantLock statusLock = new ReentrantLock();
    private final ReentrantLock auditLock = new ReentrantLock();

    public ScheduledJobs(StatusMonitor monitor, AuditService audit, AccountService accounts, NotificationService notifications) {
        this.monitor = monitor;
        this.audit = audit;
        this.accounts = accounts;
        this.notifications = notifications;
    }

    @Scheduled(cron = "0 * * * * *")
    public void checkStatus() {
        if (!statusLock.tryLock()) {
            return;
        }
        try {
            monitor.checkDue(false);
        } finally {
            statusLock.unlock();
        }
    }

    @Scheduled(cron = "0 */15 * * * *")
    public void syncAudit() {
        if (!auditLock.tryLock()) {
            return;
        }
        try {
            audit.sync();
        } finally {
            auditLock.unlock();
        }
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void prune() {
        accounts.pruneSignIns();
        notifications.prune();
    }
}
