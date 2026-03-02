package com.slackai.slackaidatabot;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Component
public class SlackResponseFormatter {

    public String format(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "📊 *Results*\n\nNo data found.";
        }

        List<String> columns = List.copyOf(rows.get(0).keySet());

        StringBuilder sb = new StringBuilder("📊 *Results*\n\n");

        // Header row
        StringJoiner header = new StringJoiner(" | ");
        columns.forEach(header::add);
        sb.append(header).append("\n");

        // Separator
        String separator = "-".repeat(header.toString().length());
        sb.append(separator).append("\n");

        // Data rows
        for (Map<String, Object> row : rows) {
            StringJoiner line = new StringJoiner(" | ");
            for (String col : columns) {
                Object val = row.get(col);
                line.add(val != null ? val.toString() : "NULL");
            }
            sb.append(line).append("\n");
        }

        return sb.toString();
    }
}
