package com.slackai.slackaidatabot;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Formats query results and other messages as Slack Block Kit JSON.
 */
@Component
public class BlockKitFormatter {

    private static final int PAGE_SIZE = 10;

    // ── Result blocks ─────────────────────────────────────────────────────────

    /**
     * Build a Block Kit message for a page of query results.
     *
     * @param rows     full result set
     * @param question original user question
     * @param page     0-based page index
     * @param queryId  UUID for pagination button action values
     */
    public Map<String, Object> buildResultBlocks(
            List<Map<String, Object>> rows, String question, int page, String queryId) {

        List<Object> blocks = new ArrayList<>();

        // ── Header ────────────────────────────────────────────────────────────
        blocks.add(section("📊 *Query results for:* _" + escMd(question) + "_"));
        blocks.add(divider());

        if (rows == null || rows.isEmpty()) {
            blocks.add(section("_No data found for this query._"));
            return message(blocks);
        }

        // ── Paged rows ────────────────────────────────────────────────────────
        int total = rows.size();
        int totalPages = (int) Math.ceil((double) total / PAGE_SIZE);
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, total);
        List<Map<String, Object>> pageRows = rows.subList(start, end);

        List<String> cols = List.copyOf(pageRows.get(0).keySet());

        // Column headers in bold
        StringJoiner header = new StringJoiner(" │ ");
        cols.forEach(c -> header.add("*" + c + "*"));
        blocks.add(section(header.toString()));

        // Data rows (grouped into chunks of 3 as field blocks for readability)
        for (Map<String, Object> row : pageRows) {
            StringJoiner line = new StringJoiner("  │  ");
            for (String col : cols) {
                Object v = row.get(col);
                line.add(v != null ? v.toString() : "—");
            }
            blocks.add(context(line.toString()));
        }

        blocks.add(divider());

        // ── Footer: row count + pagination ────────────────────────────────────
        String footerText = String.format("Showing rows %d–%d of %d  •  Page %d / %d",
                start + 1, end, total, page + 1, totalPages);
        blocks.add(context(footerText));

        // Pagination buttons
        if (totalPages > 1) {
            List<Object> buttons = new ArrayList<>();
            if (page > 0) {
                buttons.add(button("⬅ Prev", "page_prev",
                        queryId + ":" + (page - 1)));
            }
            if (page < totalPages - 1) {
                buttons.add(button("Next ➡", "page_next",
                        queryId + ":" + (page + 1)));
            }
            if (!buttons.isEmpty()) {
                blocks.add(actions(buttons));
            }
        }

        return message(blocks);
    }

    // ── Modal definition ──────────────────────────────────────────────────────

    /**
     * Builds the modal view payload for /ask-data.
     */
    public Map<String, Object> buildAskDataModal() {
        List<Object> blocks = new ArrayList<>();

        // Natural-language question input
        blocks.add(input("question_block", "question_input",
                "Your question", "e.g. What are total sales last week?", false));

        // Optional: region filter
        blocks.add(staticSelect("region_block", "region_input",
                "Filter by region (optional)",
                List.of("All", "North", "South", "East", "West")));

        // Optional: category filter
        blocks.add(staticSelect("category_block", "category_input",
                "Filter by category (optional)",
                List.of("All", "Electronics", "Apparel", "Grocery", "Fashion")));

        Map<String, Object> modal = new LinkedHashMap<>();
        modal.put("type", "modal");
        modal.put("callback_id", "ask_data_modal");
        modal.put("title", text("Ask Data Bot"));
        modal.put("submit", text("Run Query"));
        modal.put("close", text("Cancel"));
        modal.put("blocks", blocks);
        return modal;
    }

    // ── Error / info blocks ───────────────────────────────────────────────────

    public Map<String, Object> errorBlocks(String question, String errorMsg) {
        return message(List.of(
                section("❌ *Query failed* for: _" + escMd(question) + "_"),
                context("Error: " + errorMsg)));
    }

    // ── Private block builders ────────────────────────────────────────────────

    private Map<String, Object> message(List<Object> blocks) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blocks", blocks);
        return m;
    }

    private Map<String, Object> section(String mrkdwn) {
        return Map.of(
                "type", "section",
                "text", Map.of("type", "mrkdwn", "text", mrkdwn));
    }

    private Map<String, Object> context(String mrkdwn) {
        return Map.of(
                "type", "context",
                "elements", List.of(Map.of("type", "mrkdwn", "text", mrkdwn)));
    }

    private Map<String, Object> divider() {
        return Map.of("type", "divider");
    }

    private Map<String, Object> actions(List<Object> elements) {
        return Map.of("type", "actions", "elements", elements);
    }

    private Map<String, Object> button(String label, String actionId, String value) {
        return Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", label),
                "action_id", actionId,
                "value", value);
    }

    private Map<String, Object> input(String blockId, String actionId,
            String label, String placeholder, boolean multiline) {
        return Map.of(
                "type", "input",
                "block_id", blockId,
                "label", text(label),
                "element", Map.of(
                        "type", "plain_text_input",
                        "action_id", actionId,
                        "placeholder", text(placeholder),
                        "multiline", multiline));
    }

    private Map<String, Object> staticSelect(String blockId, String actionId,
            String label, List<String> options) {
        List<Map<String, Object>> opts = options.stream()
                .map(o -> (Map<String, Object>) Map.of(
                        "text", Map.of("type", "plain_text", "text", o),
                        "value", o.toLowerCase().replace(" ", "_")))
                .toList();
        return Map.of(
                "type", "input",
                "block_id", blockId,
                "optional", true,
                "label", text(label),
                "element", Map.of(
                        "type", "static_select",
                        "action_id", actionId,
                        "placeholder", text("Choose..."),
                        "options", opts));
    }

    private Map<String, Object> text(String t) {
        return Map.of("type", "plain_text", "text", t);
    }

    private String escMd(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
