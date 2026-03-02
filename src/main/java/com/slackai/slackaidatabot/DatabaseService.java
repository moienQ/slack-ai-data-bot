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
     * Schema for the primary sales_daily table (legacy single-table calls).
     */
    public List<Map<String, Object>> getTableSchema() {
        return jdbcTemplate.queryForList("""
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name   = 'sales_daily'
                ORDER BY ordinal_position
                """);
    }

    /**
     * Full schema across ALL tables in the public schema — used for multi-table /
     * auto-JOIN.
     * Returns rows: table_name, column_name, data_type
     */
    public List<Map<String, Object>> getFullSchema() {
        return jdbcTemplate.queryForList("""
                SELECT table_name, column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                ORDER BY table_name, ordinal_position
                """);
    }

    /**
     * Returns the list of tables in the public schema.
     */
    public List<String> getTableNames() {
        return jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """, String.class);
    }
}
