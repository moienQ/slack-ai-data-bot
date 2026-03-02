package com.slackai.slackaidatabot;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores full query results in memory, keyed by UUID.
 * Each entry expires after 30 minutes.
 */
@Component
public class PagedResultStore {

    private static final long TTL_MS = 30 * 60 * 1_000L; // 30 min

    record Entry(List<Map<String, Object>> rows, String question,
            String userId, long createdAt) {
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public String save(List<Map<String, Object>> rows, String question, String userId) {
        evictExpired();
        String id = UUID.randomUUID().toString();
        store.put(id, new Entry(rows, question, userId, Instant.now().toEpochMilli()));
        return id;
    }

    public Optional<Entry> get(String id) {
        Entry e = store.get(id);
        if (e == null)
            return Optional.empty();
        if (Instant.now().toEpochMilli() - e.createdAt() > TTL_MS) {
            store.remove(id);
            return Optional.empty();
        }
        return Optional.of(e);
    }

    private void evictExpired() {
        long now = Instant.now().toEpochMilli();
        store.entrySet().removeIf(e -> now - e.getValue().createdAt() > TTL_MS);
    }
}
