package ppa.cms.service;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ppa.cms.config.CmsProperties;
import ppa.cms.domain.Reachability;
import ppa.cms.domain.SystemEnvironment;
import ppa.cms.persistence.RegisteredSystem;
import ppa.cms.persistence.RegisteredSystemRepository;
import ppa.cms.persistence.StatusCheck;
import ppa.cms.persistence.StatusCheckRepository;
import ppa.cms.support.Addresses;

@Service
public class StatusMonitor {

    private static final Logger log = LoggerFactory.getLogger(StatusMonitor.class);
    private static final int MAX_BACKOFF_EXPONENT = 16;

    private final RegisteredSystemRepository systems;
    private final StatusCheckRepository checks;
    private final NotificationService notifications;
    private final CmsProperties properties;
    private final ReachabilityProbe probe;

    public StatusMonitor(
            RegisteredSystemRepository systems,
            StatusCheckRepository checks,
            NotificationService notifications,
            CmsProperties properties,
            ReachabilityProbe probe
    ) {
        this.systems = systems;
        this.checks = checks;
        this.notifications = notifications;
        this.properties = properties;
        this.probe = probe;
    }

    @Transactional
    public String checkDue(boolean all) {
        int batch = Math.max(1, properties.getSystemStatus().getBatchSize());
        Instant now = Instant.now();
        List<RegisteredSystem> due = new ArrayList<>();
        for (RegisteredSystem system : systems.findByEnvironmentOrderByNameAscIdAsc(SystemEnvironment.PRODUCTION)) {
            if (system.getDomain() == null) {
                continue;
            }
            StatusCheck check = system.getStatusCheck();
            if (all || check == null || !check.getNextCheckAt().isAfter(now)) {
                due.add(system);
            }
        }
        due.sort((left, right) -> Long.compare(left.getId(), right.getId()));
        if (due.size() > batch) {
            due = new ArrayList<>(due.subList(0, batch));
        }
        if (due.isEmpty()) {
            return "No production system is due for a check.";
        }
        Map<Long, ProbeResult> results = check(due);
        int up = 0;
        int down = 0;
        int unknown = 0;
        for (RegisteredSystem system : due) {
            ProbeResult result = results.get(system.getId());
            record(system, result);
            if (result.status() == Reachability.UP) {
                up++;
            } else if (result.status() == Reachability.DOWN) {
                down++;
            } else {
                unknown++;
            }
        }
        return "Checked " + due.size() + " system(s): " + up + " up, " + down + " down, " + unknown + " unknown.";
    }

    private Map<Long, ProbeResult> check(List<RegisteredSystem> systems) {
        Map<Long, ProbeResult> results = new LinkedHashMap<>();
        Map<Long, List<String>> pending = new LinkedHashMap<>();
        for (RegisteredSystem system : systems) {
            List<String> urls = Addresses.statusUrls(system.getDomain());
            if (urls.isEmpty()) {
                results.put(system.getId(), ProbeResult.of(Reachability.UNKNOWN, "The domain name isn't a web address that can be checked."));
            } else {
                pending.put(system.getId(), urls);
            }
        }
        int concurrency = Math.max(1, properties.getSystemStatus().getConcurrency());
        while (!pending.isEmpty()) {
            Map<Long, ProbeOutcome> outcomes = probeAll(pending, concurrency);
            Map<Long, List<String>> retry = new LinkedHashMap<>();
            for (Map.Entry<Long, List<String>> entry : pending.entrySet()) {
                ProbeOutcome outcome = outcomes.get(entry.getKey());
                if (outcome != null && outcome.response() != null) {
                    results.put(entry.getKey(), fromResponse(outcome.response(), entry.getValue().get(0)));
                } else if (outcome != null && outcome.connectionFailure() && entry.getValue().size() > 1) {
                    retry.put(entry.getKey(), entry.getValue().subList(1, entry.getValue().size()));
                } else if (outcome != null && outcome.connectionFailure()) {
                    results.put(entry.getKey(), ProbeResult.of(Reachability.DOWN, describe(outcome.failure())));
                } else {
                    log.warn("System status check could not be completed. system_id={}", entry.getKey());
                    results.put(entry.getKey(), ProbeResult.of(Reachability.UNKNOWN, "The check couldn't be completed."));
                }
            }
            pending = retry;
        }
        return results;
    }

    private Map<Long, ProbeOutcome> probeAll(Map<Long, List<String>> pending, int concurrency) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, pending.size()));
        Map<Long, Future<ProbeOutcome>> futures = new LinkedHashMap<>();
        for (Map.Entry<Long, List<String>> entry : pending.entrySet()) {
            String url = entry.getValue().get(0);
            futures.put(entry.getKey(), pool.submit(() -> probe.get(url)));
        }
        Map<Long, ProbeOutcome> outcomes = new LinkedHashMap<>();
        for (Map.Entry<Long, Future<ProbeOutcome>> entry : futures.entrySet()) {
            try {
                outcomes.put(entry.getKey(), entry.getValue().get());
            } catch (Exception exception) {
                outcomes.put(entry.getKey(), ProbeOutcome.failed(exception));
            }
        }
        pool.shutdown();
        return outcomes;
    }

    private ProbeResult fromResponse(ProbeResponse response, String url) {
        String protocol = url.startsWith("https://") ? "HTTPS" : "HTTP";
        boolean up = response.status() < 500;
        return new ProbeResult(
                up ? Reachability.UP : Reachability.DOWN,
                up ? "Responded with HTTP " + response.status() + " over " + protocol + "." : "Responded with server error HTTP " + response.status() + " over " + protocol + ".",
                response.status(),
                response.millis()
        );
    }

    private String describe(Throwable failure) {
        int timeout = properties.getSystemStatus().getTimeoutSeconds();
        Throwable cause = failure;
        while (cause != null) {
            String name = cause.getClass().getSimpleName();
            if (cause instanceof java.net.UnknownHostException || name.contains("UnknownHost")) {
                return "The domain name couldn't be resolved.";
            }
            if (cause instanceof javax.net.ssl.SSLException || name.contains("SSL")) {
                return "A secure (HTTPS) connection couldn't be established.";
            }
            if (cause instanceof HttpTimeoutException || name.contains("Timeout")) {
                return "No response within " + timeout + " seconds.";
            }
            String message = cause.getMessage() == null ? "" : cause.getMessage();
            if (message.toLowerCase(java.util.Locale.ROOT).contains("refused")) {
                return "The connection was refused.";
            }
            cause = cause.getCause();
        }
        return "Couldn't connect.";
    }

    private void record(RegisteredSystem system, ProbeResult result) {
        StatusCheck previous = checks.findBySystem_Id(system.getId()).orElse(null);
        Reachability previousStatus = previous == null ? null : previous.getStatus();
        int previousFailures = previous == null ? 0 : previous.getConsecutiveFailures();
        int interval = Math.max(1, properties.getSystemStatus().getIntervalMinutes());
        int maxBackoff = Math.max(interval, properties.getSystemStatus().getMaxBackoffMinutes());
        int failures = result.status() == Reachability.DOWN ? previousFailures + 1 : 0;
        long delay;
        if (result.status() == Reachability.UNKNOWN) {
            delay = maxBackoff;
        } else if (failures == 0) {
            delay = interval;
        } else {
            int exponent = Math.min(failures - 1, MAX_BACKOFF_EXPONENT);
            delay = Math.min(maxBackoff, interval * (1L << exponent));
        }
        Instant now = Instant.now();
        StatusCheck check = previous == null ? new StatusCheck() : previous;
        if (previous == null) {
            check.setSystem(system);
        }
        check.setStatus(result.status());
        check.setReason(result.reason());
        check.setHttpStatus(result.httpStatus());
        check.setResponseTimeMs(result.responseTimeMs());
        check.setConsecutiveFailures(failures);
        check.setCheckedAt(now);
        check.setNextCheckAt(now.plus(Duration.ofMinutes(delay)));
        checks.save(check);
        if (previousStatus != null && previousStatus != result.status()) {
            log.info("System reachability changed. system_id={} from={} to={}", system.getId(), previousStatus.value(), result.status().value());
        }
        notifications.notifyStatus(system.getName(), result.status(), previousStatus, previousFailures, failures, result.reason());
    }

    public record ProbeResult(Reachability status, String reason, Integer httpStatus, Integer responseTimeMs) {
        static ProbeResult of(Reachability status, String reason) {
            return new ProbeResult(status, reason, null, null);
        }
    }

    public record ProbeResponse(int status, int millis) {
    }

    public record ProbeOutcome(ProbeResponse response, boolean connectionFailure, Throwable failure) {
    public static ProbeOutcome failed(Throwable failure) {
            return new ProbeOutcome(null, true, failure);
        }
    }

    public interface ReachabilityProbe {
        ProbeOutcome get(String url);
    }

    @Service
    public static class HttpReachabilityProbe implements ReachabilityProbe {
        private final CmsProperties properties;
        private final java.net.http.HttpClient secure;
        private final java.net.http.HttpClient insecure;

        public HttpReachabilityProbe(CmsProperties properties) {
            this.properties = properties;
            int connect = Math.max(1, properties.getSystemStatus().getConnectTimeoutSeconds());
            this.secure = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(connect))
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .build();
            this.insecure = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(connect))
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .sslContext(trustAll())
                    .build();
        }

        @Override
        public ProbeOutcome get(String url) {
            long started = System.nanoTime();
            try {
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .timeout(Duration.ofSeconds(Math.max(1, properties.getSystemStatus().getTimeoutSeconds())))
                        .header("User-Agent", "CMS-PPA reachability check")
                        .GET()
                        .build();
                java.net.http.HttpClient client = properties.getSystemStatus().isVerifyTls() ? secure : insecure;
                java.net.http.HttpResponse<Void> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
                int millis = (int) ((System.nanoTime() - started) / 1_000_000L);
                return new ProbeOutcome(new ProbeResponse(response.statusCode(), millis), false, null);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return ProbeOutcome.failed(exception);
            } catch (Exception exception) {
                return ProbeOutcome.failed(exception);
            }
        }

        private static SSLContext trustAll() {
            try {
                TrustManager[] trust = new TrustManager[]{new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }

                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }
                }};
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trust, new java.security.SecureRandom());
                return context;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
