#!/usr/bin/env python3
"""jadx-headless-mcp 命令行客户端。

用法:
    python mcp_cli.py tools                     列出全部工具
    python mcp_cli.py call <tool> '<json参数>'   调用工具
    python mcp_cli.py load <apk路径>             加载/切换 APK

默认连 http://127.0.0.1:8660/mcp，可用环境变量 MCP_PORT 覆盖。
"""

import json
import os
import sys
import urllib.request

PORT = os.environ.get("MCP_PORT", "8660")
BASE = f"http://127.0.0.1:{PORT}/mcp"

# 关键：禁用代理。本机有系统代理 + Watt Toolkit hosts 加速，
# 走代理会让 localhost 请求被吞掉。
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

_session_id = None
_id = 0


def _rpc(method, params=None, notify=False):
    global _session_id, _id
    body = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        body["params"] = params
    if not notify:
        _id += 1
        body["id"] = _id

    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    if _session_id:
        headers["Mcp-Session-Id"] = _session_id

    req = urllib.request.Request(
        BASE, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST"
    )
    with _opener.open(req, timeout=600) as resp:
        sid = resp.headers.get("Mcp-Session-Id")
        if sid:
            _session_id = sid
        raw = resp.read().decode("utf-8", "replace")

    if not raw.strip():
        return None

    # Streamable HTTP 可能以 SSE 形式返回
    stripped = raw.lstrip()
    if stripped.startswith("event:") or stripped.startswith("data:") or "data:" in raw:
        last = None
        for line in raw.splitlines():
            line = line.strip()
            if line.startswith("data:"):
                payload = line[5:].strip()
                if payload and payload != "[DONE]":
                    last = payload
        if last:
            try:
                return json.loads(last)
            except json.JSONDecodeError:
                pass

    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        # 调试：原样输出便于定位
        sys.stderr.write("=== RAW RESPONSE (unparsable) ===\n")
        sys.stderr.write(raw[:2000] + "\n")
        sys.stderr.write("=== END RAW ===\n")
        raise


def handshake():
    _rpc("initialize", {
        "protocolVersion": "2025-06-18",
        "capabilities": {},
        "clientInfo": {"name": "spacehook-cli", "version": "1.0"},
    })
    _rpc("notifications/initialized", notify=True)


def list_tools():
    handshake()
    res = _rpc("tools/list")
    for t in res["result"]["tools"]:
        print(f"- {t['name']}: {t.get('description', '').splitlines()[0][:110]}")


def call_tool(name, args):
    handshake()
    res = _rpc("tools/call", {"name": name, "arguments": args})
    if res is None:
        print("(空响应)")
        return
    result = res.get("result", res)
    content = result.get("content") if isinstance(result, dict) else None
    if content:
        for c in content:
            if c.get("type") == "text":
                print(c["text"])
            else:
                print(json.dumps(c, ensure_ascii=False))
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    cmd = sys.argv[1]
    if cmd == "tools":
        list_tools()
    elif cmd == "call":
        tool = sys.argv[2]
        raw = sys.argv[3] if len(sys.argv) > 3 else "{}"
        call_tool(tool, json.loads(raw))
    elif cmd == "load":
        call_tool("load_apk", {"path": sys.argv[2]})
    elif cmd == "current":
        call_tool("current_apk", {})
    else:
        print(__doc__)
        sys.exit(1)
