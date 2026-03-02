package com.slackai.slackaidatabot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LangChainService {

    @Value("${langchain.service.url}")
    private String langchainServiceUrl;

    private final WebClient webClient;

    public LangChainService(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Generates SQL from a natural-language question.
     *
     * @param userQuestion the user's Slack question
     * @param userId       Slack user_id for per-user conversation memory
     * @param schema       live column definitions from information_schema (may be
     *                     null)
     */
    public String generateSql(String userQuestion, String userId, List<Map<String, Object>> schema) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("question", userQuestion);
        requestBody.put("user_id", userId != null ? userId : "anonymous");
        if (schema != null && !schema.isEmpty()) {
            requestBody.put("schema", schema);
        }

        Map<?, ?> response = webClient.post()
                .uri(langchainServiceUrl + "/generate-sql")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null || !response.containsKey("sql")) {
            throw new RuntimeException("LangChain service returned an invalid response.");
        }

        return (String) response.get("sql");
    }

    /** Full multi-table schema passed to Flask — enables auto-JOIN queries. */
    public String generateSqlMultiTable(String userQuestion, String userId,
            List<Map<String, Object>> fullSchema) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("question", userQuestion);
        requestBody.put("user_id", userId != null ? userId : "anonymous");
        requestBody.put("schema", fullSchema);
        requestBody.put("multi_table", true); // signals Flask to use multi-table prompt

        Map<?, ?> response = webClient.post()
                .uri(langchainServiceUrl + "/generate-sql")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null || !response.containsKey("sql")) {
            throw new RuntimeException("LangChain service returned an invalid response.");
        }
        return (String) response.get("sql");
    }

    /** Backward-compatible overload (no user context). */
    public String generateSql(String userQuestion) {
        return generateSql(userQuestion, "anonymous", null);
    }
}
