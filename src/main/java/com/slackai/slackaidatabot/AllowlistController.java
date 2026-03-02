package com.slackai.slackaidatabot;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * User access control via an allowlist stored in PostgreSQL.
 *
 * /slack/allowlist add @user — allow a user
 * /slack/allowlist remove @user — revoke access
 * /slack/allowlist list — show all allowed users
 *
 * If the allowlist is empty everyone is allowed (open-by-default).
 * Only users who are already in the list with is_admin=true can modify it.
 */
@RestController
public class AllowlistController {

    @Autowired
    private JdbcTemplate jdbc;

    @PostMapping(value = "/slack/allowlist", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> allowlist(
            @RequestParam(value = "text", defaultValue = "") String text,
            @RequestParam(value = "user_id", required = false) String userId,
            @RequestParam(value = "user_name", required = false) String userName) {

        if (userId == null)
            return ResponseEntity.ok("❌ Could not identify your Slack user.");

        String[] parts = text.trim().split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String target = parts.length > 1 ? parts[1].replaceAll("[<@>]", "").trim() : "";

        return switch (cmd) {
            case "add" -> handleAdd(userId, target);
            case "remove" -> handleRemove(userId, target);
            case "list" -> handleList();
            default -> ResponseEntity.ok("""
                    📋 *Allowlist commands:*
                    • `/allowlist add <user_id>` — grant access
                    • `/allowlist remove <user_id>` — revoke access
                    • `/allowlist list` — show all allowed users

                    _If the allowlist is empty, everyone can use the bot._
                    """);
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<String> handleAdd(String requesterId, String targetId) {
        if (targetId.isBlank())
            return ResponseEntity.ok("❌ Usage: `/allowlist add <user_id>`");
        if (!isAdmin(requesterId) && !isEmpty())
            return ResponseEntity.ok("❌ Only admins can modify the allowlist.");

        jdbc.update("""
                INSERT INTO public.allowed_users (user_id, added_by, is_admin)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO NOTHING
                """, targetId, requesterId, isEmpty()); // first user added becomes admin

        return ResponseEntity.ok("✅ `" + targetId + "` added to allowlist.");
    }

    private ResponseEntity<String> handleRemove(String requesterId, String targetId) {
        if (targetId.isBlank())
            return ResponseEntity.ok("❌ Usage: `/allowlist remove <user_id>`");
        if (!isAdmin(requesterId))
            return ResponseEntity.ok("❌ Only admins can modify the allowlist.");

        int rows = jdbc.update("DELETE FROM public.allowed_users WHERE user_id = ?", targetId);
        return rows > 0
                ? ResponseEntity.ok("🗑️ `" + targetId + "` removed from allowlist.")
                : ResponseEntity.ok("⚠️ `" + targetId + "` was not in the allowlist.");
    }

    private ResponseEntity<String> handleList() {
        List<String> users = jdbc.queryForList(
                "SELECT user_id || CASE WHEN is_admin THEN ' (admin)' ELSE '' END FROM public.allowed_users ORDER BY added_at",
                String.class);
        if (users.isEmpty())
            return ResponseEntity.ok("📋 Allowlist is empty — everyone can use the bot.");
        return ResponseEntity.ok("📋 *Allowed users (" + users.size() + "):*\n" +
                users.stream().map(u -> "• `" + u + "`").reduce("", (a, b) -> a + "\n" + b));
    }

    /** Check if a user is allowed (empty list = open to everyone). */
    public boolean isAllowed(String userId) {
        if (isEmpty())
            return true;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.allowed_users WHERE user_id = ?", Integer.class, userId);
        return count != null && count > 0;
    }

    private boolean isAdmin(String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.allowed_users WHERE user_id = ? AND is_admin = true", Integer.class,
                userId);
        return count != null && count > 0;
    }

    private boolean isEmpty() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM public.allowed_users", Integer.class);
        return count == null || count == 0;
    }
}
