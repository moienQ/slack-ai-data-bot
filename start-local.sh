#!/bin/bash
# start-local.sh — Start all Slack AI Data Bot services locally
# Usage: ./start-local.sh
# ─────────────────────────────────────────────────────────────

PROJECT="/Users/mohammadmoienquaraishi/Downloads/Slack Ai"

echo "🔄 Stopping any existing instances..."
lsof -ti:8080 | xargs kill -9 2>/dev/null
lsof -ti:5001 | xargs kill -9 2>/dev/null
pkill -f ngrok 2>/dev/null
sleep 2

echo "🐍 Starting Flask (port 5001)..."
python3 "$PROJECT/langchain_service/app.py" > /tmp/flask_run.log 2>&1 &
FLASK_PID=$!

echo "☕ Starting Spring Boot (port 8080)..."
cd "$PROJECT" && SLACK_SIGNING_SECRET="" mvn spring-boot:run -q > /tmp/spring_run.log 2>&1 &
SPRING_PID=$!

echo "🌐 Starting ngrok tunnel..."
ngrok http 8080 --log=stdout > /tmp/ngrok.log 2>&1 &
NGROK_PID=$!

echo "⏳ Waiting for services to start..."
sleep 10

echo ""
echo "=== STATUS CHECK ==="
curl -s http://localhost:5001/ | python3 -c "import sys,json; d=json.load(sys.stdin); print('Flask  ✅', d['message'])" 2>/dev/null || echo "Flask  ❌ Not ready yet (check /tmp/flask_run.log)"
grep -q "CONNECTED" /tmp/spring_run.log && echo "Spring ✅ DB CONNECTED" || echo "Spring ⏳ Still starting (check /tmp/spring_run.log)"
NGROK_URL=$(curl -s http://127.0.0.1:4040/api/tunnels | python3 -c "import sys,json; t=json.load(sys.stdin)['tunnels']; [print('ngrok  ✅', x['public_url']) for x in t if 'https' in x['public_url']]" 2>/dev/null)
[ -z "$NGROK_URL" ] && echo "ngrok  ❌ Check /tmp/ngrok.log" || echo "$NGROK_URL"

echo ""
echo "PIDs: Flask=$FLASK_PID  Spring=$SPRING_PID  ngrok=$NGROK_PID"
echo "Logs: /tmp/flask_run.log  /tmp/spring_run.log  /tmp/ngrok.log"
