package com.slackai.slackaidatabot;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController
public class SlackController {

    @Autowired
    private LangChainService langChainService;
    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private SlackResponseFormatter formatter;
    @Autowired
    private WebClient.Builder webClientBuilder;

    @Value("${langchain.service.url}")
    private String langchainServiceUrl;
    @Value("${slack.bot.token:}")
    private String slackBotToken;
    @Value("${slack.signing.secret:}")
    private String slackSigningSecret;

    // Stores the last SQL per channel for /export-csv
    private final ConcurrentHashMap<String, String> lastSqlByChannel = new ConcurrentHashMap<>();

    // ── /slack/ask-data ───────────────────────────────────────────────────────

    @PostMapping(value = "/slack/ask-data", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> askData(
            @RequestParam("text") String userQuestion,
            @RequestParam(value = "response_url", required = false) String responseUrl,
            @RequestParam(value = "channel_id", required = false) String channelId,
            @RequestParam(value = "user_id", required = false) String userId,
            HttpServletRequest request) {

        // Validate Slack signing secret
        if (!slackSigningSecret.isBlank() && !validateSlackRequest(request)) {
            return ResponseEntity.status(401).body("Unauthorized: invalid Slack signature.");
        }

        CompletableFuture.runAsync(() -> processAndRespond(userQuestion, responseUrl, channelId, userId));

        return ResponseEntity.ok("⏳ Processing your query: _" + userQuestion + "_");
    }

    // ── /slack/export-csv ─────────────────────────────────────────────────────

    @PostMapping(value = "/slack/export-csv", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> exportCsv(
            @RequestParam(value = "channel_id", required = false) String channelId,
            @RequestParam(value = "response_url", required = false) String responseUrl,
            HttpServletRequest request) {

        if (!slackSigningSecret.isBlank() && !validateSlackRequest(request)) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        String sql = channelId != null ? lastSqlByChannel.get(channelId) : null;
        if (sql == null) {
            return ResponseEntity.ok("No recent query found for this channel. Run `/ask-data` first.");
        }

        CompletableFuture.runAsync(() -> {
            try {
                List<Map<String, Object>> rows = databaseService.executeQuery(sql);
                String csv = toCsv(rows);
                // Post CSV inline as a code block — no files:write scope needed
                postToResponseUrl(responseUrl, "📥 *CSV Export*\n```\n" + csv + "```");
            } catch (Exception e) {
                postToResponseUrl(responseUrl, "❌ CSV export failed: " + e.getMessage());
            }
        });

        return ResponseEntity.ok("⏳ Generating CSV export...");
    }

    // ── /slack/clear-memory ───────────────────────────────────────────────────

    @PostMapping(value = "/slack/clear-memory", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> clearMemory(
            @RequestParam(value = "user_id", required = false) String userId,
            @RequestParam(value = "response_url", required = false) String responseUrl,
            HttpServletRequest request) {

        String uid = userId != null ? userId : "anonymous";
        CompletableFuture.runAsync(() -> {
            try {
                webClientBuilder.build()
                        .post().uri(langchainServiceUrl + "/clear-memory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("user_id", uid))
                        .retrieve().toBodilessEntity().block();
                postToResponseUrl(responseUrl, "🧹 Conversation memory cleared! Start a fresh query with `/ask-data`.");
            } catch (Exception e) {
                postToResponseUrl(responseUrl, "❌ Could not clear memory: " + e.getMessage());
            }
        });
        return ResponseEntity.ok("🧹 Clearing your conversation memory...");
    }

    // ── Processing ────────────────────────────────────────────────────────────

    private void processAndRespond(String userQuestion, String responseUrl, String channelId, String userId) {
        try {
            // Fetch live schema for dynamic prompt
            List<Map<String, Object>> schema = databaseService.getTableSchema();
            String sql = langChainService.generateSql(userQuestion, userId, schema);

            // Persist last SQL for this channel (for /export-csv)
            if (channelId != null)
                lastSqlByChannel.put(channelId, sql);

            List<Map<String, Object>> rows = databaseService.executeQuery(sql);
            String text = formatter.format(rows);
            postToResponseUrl(responseUrl, text);

            // Auto-generate chart if result is date+numeric with ≥3 rows
            if (channelId != null && isChartable(rows)) {
                generateAndUploadChart(rows, userQuestion, channelId);
            }

        } catch (Exception e) {
            postToResponseUrl(responseUrl, "```\nError: " + e.getMessage() + "\n```");
        }
    }

    // ── Chart ─────────────────────────────────────────────────────────────────

    private boolean isChartable(List<Map<String, Object>> rows) {
        if (rows.isEmpty() || rows.size() < 3)
            return false;
        Map<String, Object> first = rows.get(0);
        boolean hasDate = first.keySet().stream().anyMatch(k -> k.toLowerCase().contains("date")
                || k.toLowerCase().contains("day") || k.toLowerCase().contains("week"));
        boolean hasNumeric = first.values().stream().anyMatch(v -> v instanceof Number);
        return hasDate && hasNumeric;
    }

    private void generateAndUploadChart(List<Map<String, Object>> rows, String question, String channelId) {
        try {
            Map<String, Object> first = rows.get(0);
            String xCol = first.keySet().stream()
                    .filter(k -> k.toLowerCase().contains("date") || k.toLowerCase().contains("day"))
                    .findFirst().orElse(first.keySet().iterator().next());
            String yCol = first.keySet().stream()
                    .filter(k -> first.get(k) instanceof Number)
                    .findFirst().orElse(null);
            if (yCol == null)
                return;

            // Call Flask /generate-chart
            Map<String, Object> chartReq = new HashMap<>();
            chartReq.put("rows", rows);
            chartReq.put("x_col", xCol);
            chartReq.put("y_col", yCol);
            chartReq.put("title", question);

            Map<?, ?> chartResp = webClientBuilder.build()
                    .post()
                    .uri(langchainServiceUrl + "/generate-chart")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(chartReq)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (chartResp == null || !chartResp.containsKey("chart_base64"))
                return;

            byte[] pngBytes = Base64.getDecoder().decode((String) chartResp.get("chart_base64"));
            uploadFileToSlack(channelId, pngBytes, "chart.png", "image/png", "📊 Chart for: " + question);

        } catch (Exception e) {
            // chart generation is best-effort, don't surface error to user
        }
    }

    // ── Slack API Helpers ─────────────────────────────────────────────────────

    private void postToResponseUrl(String responseUrl, String text) {
        if (responseUrl == null || responseUrl.isBlank())
            return;
        String payload = "{\"text\": " + escapeJson(text) + ", \"replace_original\": false}";
        webClientBuilder.build()
                .post().uri(responseUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve().toBodilessEntity()
                .subscribe();
    }

    private void uploadFileToSlack(String channelId, byte[] content, String filename,
            String mimeType, String title) {
        if (slackBotToken.isBlank())
            return;

        org.springframework.util.MultiValueMap<String, Object> formData = new org.springframework.util.LinkedMultiValueMap<>();
        formData.add("file", new org.springframework.core.io.ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        formData.add("filename", filename);
        formData.add("channels", channelId);
        formData.add("title", title);

        webClientBuilder.build()
                .post()
                .uri("https://slack.com/api/files.upload")
                .header("Authorization", "Bearer " + slackBotToken)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(formData)
                .retrieve()
                .toBodilessEntity()
                .subscribe();
    }

    // ── Signing Secret Validation ─────────────────────────────────────────────

    private boolean validateSlackRequest(HttpServletRequest request) {
        try {
            String timestamp = request.getHeader("X-Slack-Request-Timestamp");
            String signature = request.getHeader("X-Slack-Signature");
            if (timestamp == null || signature == null)
                return false;

            // Reject requests older than 5 minutes (replay attack prevention)
            long now = System.currentTimeMillis() / 1000;
            if (Math.abs(now - Long.parseLong(timestamp)) > 300)
                return false;

            // Get raw body from ContentCachingRequestWrapper
            byte[] rawBody = new byte[0];
            if (request instanceof ContentCachingRequestWrapper wrapper) {
                rawBody = wrapper.getContentAsByteArray();
            }

            String baseString = "v0:" + timestamp + ":" + new String(rawBody, StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(slackSigningSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
            String computed = "v0=" + HexFormat.of().formatHex(hash);

            return computed.equals(signature);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String escapeJson(String text) {
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private String toCsv(List<Map<String, Object>> rows) {
        if (rows.isEmpty())
            return "No data\n";
        List<String> headers = new ArrayList<>(rows.get(0).keySet());
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", headers)).append("\n");
        for (Map<String, Object> row : rows) {
            sb.append(headers.stream()
                    .map(h -> csvEscape(row.get(h)))
                    .collect(Collectors.joining(","))).append("\n");
        }
        return sb.toString();
    }

    private String csvEscape(Object val) {
        if (val == null)
            return "";
        String s = val.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
