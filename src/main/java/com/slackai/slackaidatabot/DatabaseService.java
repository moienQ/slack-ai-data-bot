package com.slackai.slackaidatabot;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DatabaseService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> executeQuery(String sql) {
        return jdbcTemplate.queryForList(sql);
    }

    /**
     * Returns live schema for the sales_daily table from information_schema.
     * Passed to Flask so Gemini uses the real column definitions.
     */
    public List<Map<String, Object>> getTableSchema() {
        return jdbcTemplate.queryForList("""
                SELECT column_name, data_type, character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name   = 'sales_daily'
                ORDER BY ordinal_position
                """);
    }
}
