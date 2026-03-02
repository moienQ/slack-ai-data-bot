package com.slackai.slackaidatabot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class LangChainService {

    @Value("${langchain.service.url}")
    private String langchainServiceUrl;

    private final WebClient webClient;

    public LangChainService(WebClient webClient) {
        this.webClient = webClient;
    }

    public String generateSql(String userQuestion) {
        Map<String, String> requestBody = Map.of("question", userQuestion);

        Map response = webClient.post()
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
}
