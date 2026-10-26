package ppa.cms.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

public final class AttemptWindow {

    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public boolean tooMany(String key, int max, Duration window) {
        return recent(key, window).size() >= max;
    }

    public int retryAfterSeconds(String key, Duration window) {
        Deque<Instant> recent = recent(key, window);
        if (recent.isEmpty()) {
            return 1;
        }
        long seconds = Duration.between(Instant.now(), recent.peekFirst().plus(window)).toSeconds();
        return (int) Math.max(1, seconds);
    }

    public void hit(String key, Duration window) {
        Deque<Instant> deque = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            Instant now = Instant.now();
            deque.addLast(now);
            prune(deque, window, now);
        }
    }

    public void clear(String key) {
        hits.remove(key);
    }

    private Deque<Instant> recent(String key, Duration window) {
        Deque<Instant> deque = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            prune(deque, window, Instant.now());
            return new ArrayDeque<>(deque);
        }
    }

    private static void prune(Deque<Instant> deque, Duration window, Instant now) {
        while (!deque.isEmpty() && deque.peekFirst().isBefore(now.minus(window))) {
            deque.removeFirst();
        }
    }
}
