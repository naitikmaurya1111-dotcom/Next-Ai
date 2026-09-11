#!/bin/bash
set -e

# Kill any previous instance on port 8000 or cloudflared
fuser -k 8000/tcp 2>/dev/null || true
pkill -f "cloudflared tunnel --url http://127.0.0.1:8000" 2>/dev/null || true

cd /content/Next-Ai/colab

echo "Starting AGY Colab Bridge Server on port 8000..."
nohup python3 -m uvicorn main:app --host 0.0.0.0 --port 8000 > /tmp/agy_bridge.log 2>&1 &

sleep 2

# Verify server is up
curl -s http://127.0.0.1:8000/health || (echo "Failed to start server. Log:" && cat /tmp/agy_bridge.log && exit 1)

echo "Server running locally. Starting Cloudflare Tunnel to generate public WebSocket URL..."
rm -f /tmp/cloudflared.log

nohup cloudflared tunnel --url http://127.0.0.1:8000 > /tmp/cloudflared.log 2>&1 &

# Wait for URL to appear in logs
TUNNEL_URL=""
for i in {1..20}; do
    TUNNEL_URL=$(grep -o 'https://[-a-zA-Z0-9@:%._\+~#=]*\.trycloudflare\.com' /tmp/cloudflared.log | head -n 1 || true)
    if [ -n "$TUNNEL_URL" ]; then
        break
    fi
    sleep 1
done

if [ -z "$TUNNEL_URL" ]; then
    echo "Warning: Could not automatically detect Cloudflare URL yet. Log:"
    cat /tmp/cloudflared.log | tail -n 20
else
    WS_URL="${TUNNEL_URL/https:\/\//wss:\/\/}/ws"
    echo ""
    echo "==============================================================="
    echo "🎉 AGY COLAB BRIDGE IS LIVE & CONNECTED TO THIS CLI!"
    echo ""
    echo "🌐 Public HTTPS URL:  $TUNNEL_URL"
    echo "⚡ Public WebSocket:   $WS_URL"
    echo ""
    echo "👉 Enter this in Next AI app (Settings -> Server URL):"
    echo "   $WS_URL"
    echo "==============================================================="
    echo "$WS_URL" > /tmp/live_ws_url.txt
fi
