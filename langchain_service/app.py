import os
import io
import time
import base64
import requests
from collections import deque
from flask import Flask, request, jsonify
from dotenv import load_dotenv

load_dotenv()

app = Flask(__name__)

GOOGLE_API_KEY = os.environ["GOOGLE_API_KEY"]
GEMINI_MODEL = "gemini-2.5-flash-lite"
GEMINI_URL = (
    f"https://aiplatform.googleapis.com/v1/publishers/google/models/"
    f"{GEMINI_MODEL}:generateContent?key={GOOGLE_API_KEY}"
)

# ── In-memory cache (SQL results) ───────────────────────────────────────────
CACHE: dict = {}          # question_key → {sql, ts}
CACHE_TTL = 300           # 5 minutes

# ── Conversation memory (per user) ──────────────────────────────────────────
# { user_id → deque([{question, sql}, ...], maxlen=5) }
CONVERSATIONS: dict = {}
MEMORY_TURNS = 5          # How many turns to remember


def _get_conversation(user_id: str) -> deque:
    if user_id not in CONVERSATIONS:
        CONVERSATIONS[user_id] = deque(maxlen=MEMORY_TURNS)
    return CONVERSATIONS[user_id]


def _conversation_context(user_id: str) -> str:
    history = _get_conversation(user_id)
    if not history:
        return ""
    lines = ["Previous conversation (for context):"]
    for turn in history:
        lines.append(f"  Q: {turn['question']}")
        lines.append(f"  SQL: {turn['sql']}")
    return "\n".join(lines) + "\n\n"


# ── Prompt template ─────────────────────────────────────────────────────────
PROMPT_TEMPLATE = """\
You are a SQL expert. Convert the user's question into a single valid PostgreSQL SELECT statement.

{schema_section}
{conversation_context}
Rules:
- Return ONLY the SQL SELECT statement.
- No explanations, no markdown, no code fences, no trailing semicolons.
- If the user says "now filter by X" or references a previous query, use the conversation context.

User question: {question}
"""

FALLBACK_SCHEMA = """\
Table: public.sales_daily
Columns:
  - date        (date)         — calendar date
  - region      (text)         — North, South, East, West
  - category    (text)         — Electronics, Apparel, Grocery, Fashion
  - revenue     (numeric 12,2) — total revenue
  - orders      (integer)      — order count
  - created_at  (timestamptz)  — insert timestamp
"""


# ── Helpers ──────────────────────────────────────────────────────────────────

def _cache_key(question: str, user_id: str) -> str:
    return f"{user_id}::{question.strip().lower()}"


def _get_cached(question: str, user_id: str):
    key = _cache_key(question, user_id)
    entry = CACHE.get(key)
    if entry and (time.time() - entry["ts"]) < CACHE_TTL:
        return entry["sql"]
    if entry:
        del CACHE[key]
    return None


def _set_cached(question: str, user_id: str, sql: str):
    CACHE[_cache_key(question, user_id)] = {"sql": sql, "ts": time.time()}


def _is_select(sql: str) -> bool:
    return sql.strip().upper().startswith("SELECT")


def _build_schema_section(schema: list | None) -> str:
    """Build schema section from live schema or fall back to hardcoded."""
    if not schema:
        return FALLBACK_SCHEMA
    lines = ["Table: public.sales_daily", "Columns:"]
    for col in schema:
        lines.append(f"  - {col['column_name']} ({col['data_type']})")
    return "\n".join(lines)


def _call_gemini(prompt: str) -> str:
    payload = {
        "contents": [{"role": "user", "parts": [{"text": prompt}]}]
    }
    resp = requests.post(GEMINI_URL, json=payload, timeout=30)
    resp.raise_for_status()
    return resp.json()["candidates"][0]["content"]["parts"][0]["text"].strip()


# ── Routes ───────────────────────────────────────────────────────────────────

@app.route("/", methods=["GET"])
def health():
    return jsonify({
        "status": "ok",
        "message": "SQL service running ✅ (Gemini + schema discovery + memory)",
        "active_sessions": len(CONVERSATIONS),
        "cache_size": len(CACHE)
    })


@app.route("/generate-sql", methods=["POST"])
def generate_sql():
    data = request.get_json()
    question = data.get("question", "").strip()
    user_id  = data.get("user_id", "anonymous")
    schema   = data.get("schema")   # live schema from Spring Boot (optional)

    if not question:
        return jsonify({"error": "question is required"}), 400

    # 1. Cache hit (user-scoped so follow-ups bypass cache)
    cached_sql = _get_cached(question, user_id)
    if cached_sql:
        return jsonify({"sql": cached_sql, "cached": True})

    # 2. Build prompt with live schema + conversation memory
    schema_section = _build_schema_section(schema)
    conversation_context = _conversation_context(user_id)
    prompt = PROMPT_TEMPLATE.format(
        schema_section=schema_section,
        conversation_context=conversation_context,
        question=question
    )

    # 3. Call Gemini
    try:
        sql = _call_gemini(prompt)
    except Exception as e:
        return jsonify({"error": str(e)}), 500

    # 4. Safeguard
    if not _is_select(sql):
        return jsonify({"error": "Only SELECT queries are allowed."}), 400

    # 5. Store in conversation memory & cache
    _get_conversation(user_id).append({"question": question, "sql": sql})
    _set_cached(question, user_id, sql)

    return jsonify({"sql": sql, "cached": False})


@app.route("/clear-memory", methods=["POST"])
def clear_memory():
    """Clear conversation history for a user."""
    data = request.get_json()
    user_id = data.get("user_id", "anonymous")
    if user_id in CONVERSATIONS:
        del CONVERSATIONS[user_id]
    return jsonify({"message": f"Memory cleared for {user_id}"})


@app.route("/check-anomaly", methods=["POST"])
def check_anomaly():
    """
    Detect if today's revenue is anomalous vs. a rolling average.
    Body: { rows: [{date, region, category, revenue}], threshold_pct: 20 }
    Returns: { anomalies: [{...}] }
    """
    data = request.get_json()
    rows = data.get("rows", [])
    threshold_pct = float(data.get("threshold_pct", 20))

    if len(rows) < 2:
        return jsonify({"anomalies": [], "message": "Not enough data"})

    # Group by category, compute baseline (avg of all but last day) vs latest
    from collections import defaultdict
    by_category: dict = defaultdict(list)
    for r in rows:
        by_category[r.get("category", "All")].append(float(r.get("revenue", 0)))

    anomalies = []
    for cat, revenues in by_category.items():
        if len(revenues) < 2:
            continue
        baseline = sum(revenues[:-1]) / len(revenues[:-1])
        latest   = revenues[-1]
        if baseline == 0:
            continue
        change_pct = ((latest - baseline) / baseline) * 100
        if abs(change_pct) >= threshold_pct:
            direction = "📉 dropped" if change_pct < 0 else "📈 spiked"
            anomalies.append({
                "category":    cat,
                "baseline_avg": round(baseline, 2),
                "latest":       round(latest, 2),
                "change_pct":   round(change_pct, 1),
                "alert":        f"{cat} revenue {direction} {abs(change_pct):.1f}% vs baseline"
            })

    return jsonify({"anomalies": anomalies})


@app.route("/generate-chart", methods=["POST"])
def generate_chart():
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        import matplotlib.ticker as mticker
    except ImportError:
        return jsonify({"error": "matplotlib not installed"}), 500

    data = request.get_json()
    rows   = data.get("rows", [])
    x_col  = data.get("x_col")
    y_col  = data.get("y_col")
    title  = data.get("title", "Query Results")

    if not rows or not x_col or not y_col:
        return jsonify({"error": "rows, x_col, and y_col are required"}), 400

    x_vals = [str(r.get(x_col, "")) for r in rows]
    y_vals = [float(r.get(y_col) or 0) for r in rows]

    fig, ax = plt.subplots(figsize=(10, 5))
    ax.bar(x_vals, y_vals, color="#4A90D9", edgecolor="white", linewidth=0.5)
    ax.set_xlabel(x_col, fontsize=11)
    ax.set_ylabel(y_col, fontsize=11)
    ax.set_title(title, fontsize=13, fontweight="bold")
    ax.yaxis.set_major_formatter(mticker.FuncFormatter(lambda v, _: f"{v:,.0f}"))
    plt.xticks(rotation=45, ha="right", fontsize=9)
    plt.tight_layout()

    buf = io.BytesIO()
    plt.savefig(buf, format="png", dpi=120)
    buf.seek(0)
    chart_b64 = base64.b64encode(buf.read()).decode("utf-8")
    plt.close(fig)

    return jsonify({"chart_base64": chart_b64})


if __name__ == "__main__":
    port  = int(os.environ.get("PORT", 5001))
    debug = os.environ.get("FLASK_DEBUG", "false").lower() == "true"
    app.run(host="0.0.0.0", port=port, debug=debug)
