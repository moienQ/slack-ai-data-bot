package com.slackai.slackaidatabot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs scheduled reports and posts them to Slack channels.
 *
 * Reports are configurable per channel:
 * - schedule: "daily" | "weekly" (Monday) | "none"
 * - query: the natural-language question to run
 *
 * Defaults: weekly revenue breakdown, posted every Monday at 9am.
 */
@Service
public class ScheduledReportService {

    // ── Configurable subscriptions: channelId → ReportConfig ─────────────────
    record ReportConfig(String channelId, String schedule, String query) {
    }

    private final Map<String, ReportConfig> subscriptions = new ConcurrentHashMap<>();

    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private LangChainService langChainService;
    @Autowired
    private BlockKitFormatter blockKit;
    @Autowired
    private PagedResultStore resultStore;
    @Autowired
    private WebClient.Builder webClientBuilder;

    @Value("${slack.bot.token:}")
    private String slackBotToken;

    @Value("${report.default.channel:}")
    private String defaultChannel;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Scheduled triggers ────────────────────────────────────────────────────

    /** Every Monday at 9:00 AM (server timezone) */
    @Scheduled(cron = "0 0 9 * * MON")
    public void runWeeklyReports() {
        subscriptions.values().stream()
                .filter(c -> "weekly".equals(c.schedule()))
                .forEach(this::runAndPost);
    }

    /** Every day at 8:00 AM */
    @Scheduled(cron = "0 0 8 * * *")
    public void runDailyReports() {
        subscriptions.values().stream()
                .filter(c -> "daily".equals(c.schedule()))
                .forEach(this::runAndPost);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Register or update a channel subscription. */
    public void subscribe(String channelId, String schedule, String query) {
        subscriptions.put(channelId, new ReportConfig(channelId, schedule, query));
    }

    /** Remove a channel subscription. */
    public void unsubscribe(String channelId) {
        subscriptions.remove(channelId);
    }

    /** Immediately run and post the report for a channel (for /report-now). */
    public String runNow(String channelId) {
        ReportConfig config = subscriptions.get(channelId);
        if (config == null) {
            // Default report if no subscription set
            config = new ReportConfig(channelId, "once",
                    "Show total revenue by region and category for last 7 days");
        }
        runAndPost(config);
        return "📤 Report sent to <#" + channelId + ">!";
    }

    /** Return active subscriptions for display. */
    public Map<String, ReportConfig> getSubscriptions() {
        return Collections.unmodifiableMap(subscriptions);
    }

    // ── Core runner ───────────────────────────────────────────────────────────

    private void runAndPost(ReportConfig config) {
        try {
            String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
            String fullQuery = config.query() + " (as of " + dateStr + ")";

            List<Map<String, Object>> schema = databaseService.getTableSchema();
            String sql = langChainService.generateSql(fullQuery, "scheduler", schema);
            List<Map<String, Object>> rows = databaseService.executeQuery(sql);

            String queryId = resultStore.save(rows, fullQuery, "scheduler");

            // Build Block Kit payload
            Map<String, Object> blocks = blockKit.buildResultBlocks(rows, fullQuery, 0, queryId);
            blocks.put("channel", config.channelId());
            blocks.put("text", "📅 Scheduled Report: " + config.query());

            // Prepend a header section with schedule info
            addReportHeader(blocks, config);

            postToSlack("chat.postMessage", blocks);
        } catch (Exception e) {
            System.err.println("Scheduled report failed for " + config.channelId() + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void addReportHeader(Map<String, Object> blocks, ReportConfig config) {
        List<Object> blockList = (List<Object>) blocks.get("blocks");
        if (blockList == null)
            return;
        String emoji = "weekly".equals(config.schedule()) ? "📅 *Weekly Report*" : "📆 *Daily Report*";
        Map<String, Object> header = Map.of(
                "type", "section",
                "text", Map.of("type", "mrkdwn",
                        "text", emoji + " — auto-generated · " +
                                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d"))));
        blockList.add(0, header);
        blockList.add(1, Map.of("type", "divider"));
    }

    private void postToSlack(String method, Object body) {
        if (slackBotToken.isBlank()) {
            System.err.println("⚠️  SLACK_BOT_TOKEN not set — cannot post scheduled report.");
            return;
        }
        webClientBuilder.build()
                .post()
                .uri("https://slack.com/api/" + method)
                .header("Authorization", "Bearer " + slackBotToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().toBodilessEntity().block();
    }
}
