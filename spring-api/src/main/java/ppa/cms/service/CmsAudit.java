package ppa.cms.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ppa.cms.domain.CmsAuditAction;
import ppa.cms.persistence.CmsAuditEvent;
import ppa.cms.persistence.CmsAuditEventRepository;
import ppa.cms.persistence.UserAccount;
import ppa.cms.support.JsonCodec;

@Service
public class CmsAudit {

    private final CmsAuditEventRepository events;

    public CmsAudit(CmsAuditEventRepository events) {
        this.events = events;
    }

    @Transactional
    public void record(UserAccount actor, CmsAuditAction action, String summary, String subjectLabel, String subjectType, Long subjectId, Map<String, Object> details) {
        CmsAuditEvent event = new CmsAuditEvent();
        event.setOccurredAt(Instant.now());
        event.setActorId(actor.getId());
        event.setActorName(actor.getName());
        event.setActorUsername(actor.getUsername());
        event.setAction(action.value());
        event.setModule(action.module().value());
        event.setSubjectType(subjectType);
        event.setSubjectId(subjectType == null ? null : subjectId);
        event.setSubjectLabel(subjectLabel);
        event.setSummary(summary);
        Map<String, Object> safe = safeDetails(details);
        event.setDetails(safe == null ? null : JsonCodec.write(safe));
        events.save(event);
    }

    private static Map<String, Object> safeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        Object remoteDesk = details.get("remote_desk");
        if (remoteDesk instanceof String text && !text.isBlank()) {
            safe.put("remote_desk", text);
        }
        Object changed = details.get("changed");
        if (changed instanceof List<?> fields) {
            List<String> names = new ArrayList<>();
            for (Object field : fields) {
                if (field instanceof String text && !text.isBlank()) {
                    names.add(text);
                }
            }
            if (!names.isEmpty()) {
                safe.put("changed", names);
            }
        }
        return safe.isEmpty() ? null : safe;
    }
}
