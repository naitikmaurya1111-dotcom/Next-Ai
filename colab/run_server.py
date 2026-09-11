#!/usr/bin/env python3
import asyncio
import os
import re
import signal
import subprocess
import sys
import time

def kill_port(port):
    try:
        res = subprocess.run(["fuser", f"{port}/tcp"], capture_output=True, text=True)
        pids = res.stdout.strip().split()
        for pid in pids:
            if pid:
                os.kill(int(pid), signal.SIGKILL)
    except Exception:
        pass

def main():
    kill_port(8000)
    subprocess.run(["pkill", "-9", "-f", "cloudflared"], stderr=subprocess.DEVNULL)
    time.sleep(1)

    log_path = "/tmp/agy_server.log"
    cf_log_path = "/tmp/agy_cloudflared.log"

    out_file = open(log_path, "w")
    cf_out = open(cf_log_path, "w")

    print("[*] Starting Uvicorn FastAPI server on 0.0.0.0:8000...")
    uvicorn_proc = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"],
        cwd="/content/Next-Ai/colab",
        stdout=out_file,
        stderr=out_file,
        start_new_session=True
    )

    # Wait for server to start
    server_ready = False
    for _ in range(20):
        time.sleep(0.5)
        try:
            import urllib.request
            with urllib.request.urlopen("http://127.0.0.1:8000/health", timeout=1) as resp:
                if resp.status == 200:
                    server_ready = True
                    break
        except Exception:
            pass

    if not server_ready:
        print("[!] Server failed to respond on :8000. Logs:")
        out_file.flush()
        with open(log_path) as f:
            print(f.read())
        sys.exit(1)

    print("[+] Local server is healthy!")

    print("[*] Starting Cloudflare tunnel to generate public WebSocket URL...")
    cf_proc = subprocess.Popen(
        ["cloudflared", "tunnel", "--url", "http://127.0.0.1:8000"],
        stdout=cf_out,
        stderr=cf_out,
        start_new_session=True
    )

    public_url = None
    for _ in range(30):
        time.sleep(1)
        cf_out.flush()
        with open(cf_log_path, "r", errors="replace") as f:
            content = f.read()
            match = re.search(r"https://[a-zA-Z0-9-]+\.trycloudflare\.com", content)
            if match:
                public_url = match.group(0)
                break

    if public_url:
        ws_url = public_url.replace("https://", "wss://") + "/ws"
        print("\n" + "=" * 65)
        print("🎉 NEXT AI BRIDGE IS LIVE AND CONNECTED TO YOUR AGY CLI!")
        print(f"🌐 Public Web URL:       {public_url}")
        print(f"⚡ Public WebSocket URL: {ws_url}")
        print("=" * 65)
        print("\n👉 Paste this WebSocket URL into the Next AI Android App:")
        print(f"   {ws_url}\n")
        with open("/tmp/live_ws_url.txt", "w") as f:
            f.write(ws_url + "\n")
    else:
        print("[!] Tunnel starting in background. Check /tmp/agy_cloudflared.log for URL.")

if __name__ == "__main__":
    main()
