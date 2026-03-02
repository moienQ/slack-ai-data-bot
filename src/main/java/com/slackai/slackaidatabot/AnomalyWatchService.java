package com.slackai.slackaidatabot;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches for revenue anomalies and posts Block Kit alerts to Slack.
 *
 * /watch-anomaly [threshold_pct] — register this channel for hourly checks
 * /watch-anomaly off — stop watching
 *
 * Cron: every hour, call Flask /check-anomaly, post alert if anomalies found.
 */
@Service
@RestController
public class AnomalyWatchService {

    record WatchConfig(String channelId, double thresholdPct) {
    }

    private final Map<String, WatchConfig> watchers = new ConcurrentHashMap<>();

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WebClient.Builder webClientBuilder;

    @Value("${slack.bot.token:}")
    private String slackBotToken;

    @Value("${langchain.service.url}")
    private String langchainServiceUrl;

    // ── /slack/watch-anomaly ──────────────────────────────────────────────────

    @PostMapping(value = "/slack/watch-anomaly", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> watchAnomaly(
            @RequestParam(value = "text", defaultValue = "") String text,
            @RequestParam(value = "channel_id", required = false) String channelId) {

        if (channelId == null)
            return ResponseEntity.ok("❌ Could not determine channel.");

        if ("off".equalsIgnoreCase(text.trim())) {
            watchers.remove(channelId);
            return ResponseEntity.ok("🔕 Anomaly watching stopped for this channel.");
        }

        double threshold = 20.0;
        try {
            threshold = Double.parseDouble(text.trim());
        } catch (NumberFormatException ignored) {
        }

        watchers.put(channelId, new WatchConfig(channelId, threshold));
        return ResponseEntity.ok(
                "🚨 Anomaly watching *enabled!*\n" +
                        "• Channel: <#" + channelId + ">\n" +
                        "• Threshold: *" + threshold + "%* day-over-day change\n" +
                        "• Checks: *every hour*\n" +
                        "Use `/watch-anomaly off` to stop.");
    }

    // ── Hourly anomaly check ──────────────────────────────────────────────────

    @Scheduled(cron = "0 0 * * * *") // every hour
    public void runAnomalyChecks() {
        if (watchers.isEmpty())
            return;

        // Fetch last 8 days of revenue by category
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                    SELECT date, category, SUM(revenue) AS revenue
                    FROM public.sales_daily
                    WHERE date >= CURRENT_DATE - INTERVAL '8 days'
                    GROUP BY date, category
                    ORDER BY date
                    """);
        } catch (Exception e) {
            System.err.println("Anomaly check DB query failed: " + e.getMessage());
            return;
        }

        if (rows.isEmpty())
            return;

        watchers.values().forEach(cfg -> checkAndAlert(cfg, rows));
    }

    private void checkAndAlert(WatchConfig cfg, List<Map<String, Object>> rows) {
        try {
            Map<String, Object> body = Map.of(
                    "rows", rows,
                    "threshold_pct", cfg.thresholdPct());

            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClientBuilder.build()
                    .post().uri(langchainServiceUrl + "/check-anomaly")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve().bodyToMono(Map.class).block();

            if (response == null)
                return;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> anomalies = (List<Map<String, Object>>) response.get("anomalies");
            if (anomalies == null || anomalies.isEmpty())
                return;

            // Build and post Block Kit alert
            List<Object> blocks = new java.util.ArrayList<>();
            blocks.add(Map.of("type", "section",
                    "text", Map.of("type", "mrkdwn",
                            "text",
                            "🚨 *Revenue Anomaly Alert!* — " + anomalies.size() + " anomaly/anomalies detected")));
            blocks.add(Map.of("type", "divider"));

            for (Map<String, Object> a : anomalies) {
                blocks.add(Map.of("type", "section",
                        "text", Map.of("type", "mrkdwn",
                                "text", "• " + a.get("alert") + "\n" +
                                        "  Baseline avg: *" + a.get("baseline_avg") + "* → Latest: *" + a.get("latest")
                                        + "*")));
            }

            Map<String, Object> message = new java.util.LinkedHashMap<>();
            message.put("channel", cfg.channelId());
            message.put("text", "🚨 Revenue anomaly detected!");
            message.put("blocks", blocks);
            postToSlack("chat.postMessage", message);

        } catch (Exception e) {
            System.err.println("Anomaly alert failed for " + cfg.channelId() + ": " + e.getMessage());
        }
    }

    private void postToSlack(String method, Object body) {
        if (slackBotToken.isBlank())
            return;
        webClientBuilder.build()
                .post().uri("https://slack.com/api/" + method)
                .header("Authorization", "Bearer " + slackBotToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().toBodilessEntity().block();
    }
}
