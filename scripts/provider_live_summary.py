#!/usr/bin/env python3
"""One-command provider inventory and domain summary runner."""

from __future__ import annotations

import json
import re
import socket
import ssl
import time
from datetime import datetime, timezone
from http.client import HTTPConnection, HTTPSConnection
from pathlib import Path
from urllib.parse import urlparse

EXCLUDED = {".git", ".github", ".gradle", ".idea", ".vscode", "build", "builds", "docs", "gradle", "healthcheck", "scripts", "templates"}
MAIN_URL_RE = re.compile(r"override\s+(?:var|val)\s+mainUrl\s*=\s*\"([^\"]+)\"")
NAME_RE = re.compile(r"override\s+val\s+name\s*=\s*\"([^\"]+)\"")


def modules(root: Path):
    return [item for item in sorted(root.iterdir(), key=lambda x: x.name.lower()) if item.is_dir() and item.name not in EXCLUDED and (item / "build.gradle.kts").is_file()]


def scan(module: Path, root: Path):
    names, urls = set(), set()
    for kt in module.rglob("*.kt"):
        if "build" in kt.parts:
            continue
        text = kt.read_text(encoding="utf-8", errors="ignore")
        names.update(NAME_RE.findall(text))
        urls.update(MAIN_URL_RE.findall(text))
    name = sorted(names)[0] if names else module.name
    return {"module": module.name, "name": name, "mainUrls": sorted(urls)}


def check(url: str):
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        return {"status": "UNKNOWN", "statusCode": None, "error": "bad url"}
    path = parsed.path or "/"
    if parsed.query:
        path += "?" + parsed.query
    conn_cls = HTTPSConnection if parsed.scheme == "https" else HTTPConnection
    start = time.monotonic()
    conn = None
    try:
        conn = conn_cls(parsed.netloc, timeout=12)
        conn.request("GET", path, headers={"User-Agent": "BetbetMiro-ProviderCheck/1.0", "Accept": "*/*"})
        res = conn.getresponse()
        res.read(1024)
        code = res.status
        elapsed = int((time.monotonic() - start) * 1000)
        if code in {200, 301, 302, 303, 307, 308, 403}:
            state = "GOOD"
        elif code in {404} or code >= 500:
            state = "BROKEN"
        else:
            state = "DEGRADED"
        return {"status": state, "statusCode": code, "timeMs": elapsed, "error": None}
    except (socket.timeout, TimeoutError):
        return {"status": "BROKEN", "statusCode": None, "error": "timeout"}
    except (OSError, ssl.SSLError) as exc:
        return {"status": "BROKEN", "statusCode": None, "error": str(exc)}
    finally:
        if conn:
            conn.close()


def main() -> int:
    root = Path(".").resolve()
    rows = []
    for mod in modules(root):
        item = scan(mod, root)
        urls = item["mainUrls"]
        if not urls:
            rows.append({**item, "overall": "UNKNOWN", "checks": []})
            continue
        checks = [{"url": url, **check(url)} for url in urls]
        states = [c["status"] for c in checks]
        overall = "BROKEN" if "BROKEN" in states else ("DEGRADED" if "DEGRADED" in states else "GOOD")
        rows.append({**item, "overall": overall, "checks": checks})

    payload = {"generatedAt": datetime.now(timezone.utc).isoformat(), "count": len(rows), "providers": rows}
    Path("provider-live-summary.json").write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    lines = ["# Provider Live Summary", "", f"Generated: `{payload['generatedAt']}`", "", "| Provider | Module | Overall | mainUrl | HTTP/Error |", "|---|---|---:|---|---|"]
    for item in rows:
        if not item["checks"]:
            lines.append(f"| {item['name']} | {item['module']} | UNKNOWN |  | no mainUrl |")
        for c in item["checks"]:
            info = str(c.get("statusCode") or c.get("error") or "")
            lines.append(f"| {item['name']} | {item['module']} | {item['overall']} | {c['url']} | {info} |")
    Path("provider-live-summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Provider entries: {len(rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
