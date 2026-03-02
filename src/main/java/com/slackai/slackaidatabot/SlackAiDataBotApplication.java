package com.slackai.slackaidatabot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
public class SlackAiDataBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(SlackAiDataBotApplication.class, args);
    }

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Bean
    CommandLineRunner dbCheck(JdbcTemplate jdbc) {
        return args -> {
            System.out.println("=== DB CONFIG CHECK ===");
            System.out.println("  datasource.url   = " + datasourceUrl);
            try {
                Integer result = jdbc.queryForObject("SELECT 1", Integer.class);
                System.out.println("  DB ping result   = " + result + "  ✅ CONNECTED");
            } catch (Exception e) {
                System.out.println("  DB ping FAILED   = " + e.getMessage());
            }
            System.out.println("=======================");
        };
    }
}
