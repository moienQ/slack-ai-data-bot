import os
import io
import time
import base64
import requests
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

# ── In-memory cache ────────────────────────────────────────────────────────────
CACHE: dict = {}          # question_key → {"sql": str, "ts": float}
CACHE_TTL = 300           # seconds (5 min)

PROMPT_TEMPLATE = """\
You are a SQL expert. Convert the user's question into a single valid PostgreSQL SELECT statement.

Table: public.sales_daily
Columns:
  - date        (date)         — the calendar date of the sales record
  - region      (text)         — sales region (e.g. North, South, East, West)
  - category    (text)         — product category (e.g. Electronics, Apparel, Grocery, Fashion)
  - revenue     (numeric 12,2) — total revenue for that day/region/category
  - orders      (integer)      — total number of orders
  - created_at  (timestamptz)  — when the record was inserted

Rules:
- Return ONLY the SQL SELECT statement.
- No explanations.
- No markdown formatting.
- No code fences.
- No trailing semicolons.

User question: {question}
"""


# ── Helpers ───────────────────────────────────────────────────────────────────

def _cache_key(question: str) -> str:
    return question.strip().lower()


def _get_cached(question: str):
    key = _cache_key(question)
    entry = CACHE.get(key)
    if entry and (time.time() - entry["ts"]) < CACHE_TTL:
        return entry["sql"]
    if entry:
        del CACHE[key]
    return None


def _set_cached(question: str, sql: str):
    CACHE[_cache_key(question)] = {"sql": sql, "ts": time.time()}


def _is_select(sql: str) -> bool:
    return sql.strip().upper().startswith("SELECT")


def _call_gemini(question: str) -> str:
    payload = {
        "contents": [{"role": "user", "parts": [{"text": PROMPT_TEMPLATE.format(question=question)}]}]
    }
    resp = requests.post(GEMINI_URL, json=payload, timeout=30)
    resp.raise_for_status()
    return resp.json()["candidates"][0]["content"]["parts"][0]["text"].strip()


# ── Routes ────────────────────────────────────────────────────────────────────

@app.route("/", methods=["GET"])
def health():
    return jsonify({"status": "ok", "message": "SQL service running ✅ (Gemini + caching)"})


@app.route("/generate-sql", methods=["POST"])
def generate_sql():
    data = request.get_json()
    question = data.get("question", "").strip()
    if not question:
        return jsonify({"error": "question is required"}), 400

    # 1. Cache hit?
    cached_sql = _get_cached(question)
    if cached_sql:
        return jsonify({"sql": cached_sql, "cached": True})

    # 2. Call Gemini
    try:
        sql = _call_gemini(question)
    except Exception as e:
        return jsonify({"error": str(e)}), 500

    # 3. Safeguard — SELECT only
    if not _is_select(sql):
        return jsonify({"error": "Only SELECT queries are allowed. Generated SQL was rejected."}), 400

    # 4. Cache & return
    _set_cached(question, sql)
    return jsonify({"sql": sql, "cached": False})


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
    bars = ax.bar(x_vals, y_vals, color="#4A90D9", edgecolor="white", linewidth=0.5)
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
    port = int(os.environ.get("PORT", 5001))
    debug = os.environ.get("FLASK_DEBUG", "false").lower() == "true"
    app.run(host="0.0.0.0", port=port, debug=debug)
