package com.slackai.slackaidatabot;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Slack slash commands for managing scheduled reports.
 *
 * /schedule-report [daily|weekly|off] [question]
 * e.g. /schedule-report weekly Show revenue by region for last 7 days
 *
 * /report-now
 * Triggers the report immediately for the current channel.
 */
@RestController
public class ScheduledReportController {

    @Autowired
    private ScheduledReportService reportService;

    // ── /slack/schedule-report ────────────────────────────────────────────────

    @PostMapping(value = "/slack/schedule-report", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> scheduleReport(
            @RequestParam(value = "text", defaultValue = "") String text,
            @RequestParam(value = "channel_id", required = false) String channelId,
            @RequestParam(value = "user_id", required = false) String userId) {

        if (channelId == null) {
            return ResponseEntity.ok("❌ Could not determine channel.");
        }

        String trimmed = text.trim();

        // Show current subscriptions if no args
        if (trimmed.isEmpty()) {
            return ResponseEntity.ok(buildStatusMessage(channelId));
        }

        String[] parts = trimmed.split("\\s+", 2);
        String schedule = parts[0].toLowerCase();

        // Turn off
        if ("off".equals(schedule) || "none".equals(schedule) || "stop".equals(schedule)) {
            reportService.unsubscribe(channelId);
            return ResponseEntity.ok("🔕 Scheduled reports turned *off* for this channel.");
        }

        // Validate schedule type
        if (!"daily".equals(schedule) && !"weekly".equals(schedule)) {
            return ResponseEntity.ok("""
                    Usage: `/schedule-report [daily|weekly|off] [your question]`
                    Examples:
                    • `/schedule-report weekly Show revenue by region for last 7 days`
                    • `/schedule-report daily Show total orders and revenue for today`
                    • `/schedule-report off` — stop reports for this channel
                    """);
        }

        String query = parts.length > 1 ? parts[1].trim()
                : "Show total revenue and orders by region for last 7 days";

        reportService.subscribe(channelId, schedule, query);

        String when = "weekly".equals(schedule)
                ? "every *Monday at 9:00 AM*"
                : "every day at *8:00 AM*";

        return ResponseEntity.ok(
                "✅ Scheduled report set!\n" +
                        "• *Channel:* <#" + channelId + ">\n" +
                        "• *Frequency:* " + schedule + " (" + when + ")\n" +
                        "• *Report:* " + query + "\n\n" +
                        "Use `/report-now` to test it immediately.");
    }

    // ── /slack/report-now ─────────────────────────────────────────────────────

    @PostMapping(value = "/slack/report-now", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> reportNow(
            @RequestParam(value = "text", defaultValue = "") String text,
            @RequestParam(value = "channel_id", required = false) String channelId) {

        if (channelId == null) {
            return ResponseEntity.ok("❌ Could not determine channel.");
        }

        // Optionally override the query for a one-off report
        if (!text.isBlank()) {
            reportService.subscribe(channelId, "once", text.trim());
        }

        String result = reportService.runNow(channelId);
        return ResponseEntity.ok("⏳ Generating report... " + result);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildStatusMessage(String channelId) {
        Map<String, ScheduledReportService.ReportConfig> subs = reportService.getSubscriptions();
        if (!subs.containsKey(channelId)) {
            return """
                    📅 *No scheduled report for this channel.*

                    Set one with:
                    `/schedule-report weekly Show revenue by region for last 7 days`
                    `/schedule-report daily Show total orders and revenue for today`
                    """;
        }
        ScheduledReportService.ReportConfig cfg = subs.get(channelId);
        return "📅 *Current scheduled report:*\n" +
                "• Frequency: *" + cfg.schedule() + "*\n" +
                "• Query: _" + cfg.query() + "_\n\n" +
                "Run `/schedule-report off` to cancel.";
    }
}
