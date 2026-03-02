package com.slackai.slackaidatabot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Handles Slack interactive payloads:
 * - block_actions → pagination buttons (Prev / Next page)
 * - view_submission → modal form submitted by user
 */
@RestController
public class SlackInteractionController {

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

    @Value("${langchain.service.url}")
    private String langchainServiceUrl;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── /slack/interactions ───────────────────────────────────────────────────
    @PostMapping(value = "/slack/interactions", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> handleInteraction(@RequestParam("payload") String payloadJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = mapper.readValue(payloadJson, Map.class);
            String type = (String) payload.get("type");

            if ("block_actions".equals(type)) {
                handleBlockAction(payload);
            } else if ("view_submission".equals(type)) {
                handleViewSubmission(payload);
                // Return empty body — Slack closes the modal
                return ResponseEntity.ok("");
            }
        } catch (Exception e) {
            System.err.println("Interaction error: " + e.getMessage());
        }
        return ResponseEntity.ok("");
    }

    // ── Block action: pagination buttons ─────────────────────────────────────
    @SuppressWarnings("unchecked")
    private void handleBlockAction(Map<String, Object> payload) {
        List<Map<String, Object>> actions = (List<Map<String, Object>>) payload.get("actions");
        if (actions == null || actions.isEmpty())
            return;

        Map<String, Object> action = actions.get(0);
        String actionId = (String) action.get("action_id");
        String value = (String) action.get("value"); // "queryId:page"

        if (!actionId.startsWith("page_"))
            return;

        String[] parts = value.split(":");
        String queryId = parts[0];
        int page = Integer.parseInt(parts[1]);

        // response_url comes directly from payload
        String responseUrl = null;
        if (payload.containsKey("response_url")) {
            responseUrl = (String) payload.get("response_url");
        }

        final String finalResponseUrl = responseUrl;
        CompletableFuture.runAsync(() -> {
            resultStore.get(queryId).ifPresentOrElse(entry -> {
                Map<String, Object> blocks = blockKit.buildResultBlocks(
                        entry.rows(), entry.question(), page, queryId);
                blocks.put("replace_original", true);
                postToUrl(finalResponseUrl, blocks);
            }, () -> postToUrl(finalResponseUrl,
                    Map.of("text", "⚠️ Results expired — please run the query again.",
                            "replace_original", true)));
        });
    }

    // ── View submission: modal form ───────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private void handleViewSubmission(Map<String, Object> payload) {
        Map<String, Object> view = (Map<String, Object>) payload.get("view");
        Map<String, Object> values = (Map<String, Object>) ((Map<String, Object>) view.get("state")).get("values");

        // Extract question
        String question = (String) ((Map<String, Object>) ((Map<String, Object>) values.get("question_block"))
                .get("question_input"))
                .get("value");
        if (question == null || question.isBlank())
            return;

        // Extract optional filters
        String region = getSelectValue(values, "region_block", "region_input");
        String category = getSelectValue(values, "category_block", "category_input");

        // Build enriched question if filters chosen
        StringBuilder enriched = new StringBuilder(question);
        if (region != null && !"all".equals(region)) {
            enriched.append(" (region = ").append(region).append(")");
        }
        if (category != null && !"all".equals(category)) {
            enriched.append(" (category = ").append(category).append(")");
        }

        // Get user & channel from payload
        Map<String, Object> user = (Map<String, Object>) payload.get("user");
        String userId = user != null ? (String) user.get("id") : "anonymous";

        // Async: generate SQL → execute → post to user's DM
        final String finalQuestion = enriched.toString();
        CompletableFuture.runAsync(() -> {
            try {
                List<Map<String, Object>> schema = databaseService.getTableSchema();
                String sql = langChainService.generateSql(finalQuestion, userId, schema);
                List<Map<String, Object>> rows = databaseService.executeQuery(sql);

                String queryId = resultStore.save(rows, finalQuestion, userId);
                Map<String, Object> blocks = blockKit.buildResultBlocks(rows, finalQuestion, 0, queryId);

                // Post to Slack via chat.postMessage (DM to user)
                blocks.put("channel", userId);
                blocks.put("text", "Results for: " + finalQuestion);
                postToSlackApi("chat.postMessage", blocks);
            } catch (Exception e) {
                Map<String, Object> err = blockKit.errorBlocks(finalQuestion, e.getMessage());
                err.put("channel", userId);
                err.put("text", "Error: " + e.getMessage());
                postToSlackApi("chat.postMessage", err);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String getSelectValue(Map<String, Object> values, String blockId, String actionId) {
        try {
            Map<String, Object> block = (Map<String, Object>) values.get(blockId);
            Map<String, Object> action = (Map<String, Object>) block.get(actionId);
            Map<String, Object> sel = (Map<String, Object>) action.get("selected_option");
            return sel != null ? (String) sel.get("value") : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void postToUrl(String url, Object body) {
        if (url == null || url.isBlank())
            return;
        try {
            webClientBuilder.build()
                    .post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve().toBodilessEntity().block();
        } catch (Exception e) {
            System.err.println("postToUrl error: " + e.getMessage());
        }
    }

    private void postToSlackApi(String method, Object body) {
        if (slackBotToken.isBlank())
            return;
        try {
            webClientBuilder.build()
                    .post()
                    .uri("https://slack.com/api/" + method)
                    .header("Authorization", "Bearer " + slackBotToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve().toBodilessEntity().block();
        } catch (Exception e) {
            System.err.println("Slack API error: " + e.getMessage());
        }
    }
}
