#!/usr/bin/env python3
"""Read-only player page health checker.

This script checks explicit player/episode/detail URLs from runtime health config.
It does not prove CloudStream playback and does not modify repository files.
"""

from __future__ import annotations

import argparse
import json
import socket
import ssl
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from http.client import HTTPConnection, HTTPSConnection, HTTPResponse
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

DEFAULT_TIMEOUT_SECONDS = 15
DEFAULT_USER_AGENT = "BetbetMiro-RuntimeHealthCheck/1.0"
MIN_RESPONSE_BYTES = 200
PLAYER_MARKERS = ["iframe", "embed", "source", "player", "video", "script"]


@dataclass
class PlayerPageResult:
    name: str
    enabled: bool
    ok: bool
    status: str
    targetType: str | None
    url: str | None
    statusCode: int | None
    responseTimeMs: int | None
    responseBytes: int | None
    matchedMarkers: list[str]
    blockedKeywords: list[str]
    error: str | None
    notes: str | None


def load_providers(path: Path) -> list[dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    providers = data.get("providers") if isinstance(data, dict) else data
    if not isinstance(providers, list):
        raise ValueError("Config must contain providers array or be an array")
    return providers


def pick_target(provider: dict[str, Any]) -> tuple[str | None, str | None]:
    for key, label in (("playerUrl", "player"), ("episodeUrl", "episode"), ("detailUrl", "detail")):
        value = str(provider.get(key, "")).strip()
        if value:
            return value, label
    return None, None


def http_get(url: str, provider: dict[str, Any], timeout: int) -> tuple[int | None, bytes, int | None, str | None]:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        return None, b"", None, f"unsupported URL scheme: {parsed.scheme}"
    if not parsed.netloc:
        return None, b"", None, "missing host"

    path = parsed.path or "/"
    if parsed.query:
        path += "?" + parsed.query

    headers = {
        "User-Agent": str(provider.get("userAgent") or DEFAULT_USER_AGENT),
        "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
    }
    if provider.get("referer"):
        headers["Referer"] = str(provider["referer"])
    if provider.get("origin"):
        headers["Origin"] = str(provider["origin"])

    connection_class = HTTPSConnection if parsed.scheme == "https" else HTTPConnection
    connection: HTTPSConnection | HTTPConnection | None = None
    start = time.monotonic()
    try:
        connection = connection_class(parsed.netloc, timeout=timeout)
        connection.request("GET", path, headers=headers)
        response: HTTPResponse = connection.getresponse()
        body = response.read(512_000)
        elapsed_ms = int((time.monotonic() - start) * 1000)
        return response.status, body, elapsed_ms, None
    except (socket.timeout, TimeoutError) as exc:
        return None, b"", None, f"timeout: {exc}"
    except (OSError, ssl.SSLError) as exc:
        return None, b"", None, str(exc)
    finally:
        if connection is not None:
            connection.close()


def classify(status_code: int | None, body: bytes, error: str | None, markers: list[str], blocked: list[str]) -> tuple[bool, str]:
    if error:
        if "timeout" in error.lower():
            return False, "PLAYER_PAGE_TIMEOUT"
        return False, "PLAYER_PAGE_ERROR"
    if status_code is None:
        return False, "PLAYER_PAGE_ERROR"
    if status_code == 403:
        return False, "PLAYER_PAGE_BLOCKED"
    if status_code == 404:
        return False, "PLAYER_PAGE_NOT_FOUND"
    if 500 <= status_code <= 599:
        return False, "PLAYER_PAGE_SERVER_ERROR"
    if not (200 <= status_code <= 399):
        return False, f"PLAYER_PAGE_HTTP_{status_code}"
    if blocked:
        return False, "PLAYER_PAGE_BLOCKED_KEYWORD"
    if len(body) < MIN_RESPONSE_BYTES:
        return False, "PLAYER_PAGE_EMPTY"
    if markers:
        return True, "PLAYER_PAGE_OK"
    return True, "PLAYER_PAGE_RESPONSE_OK"


def check_provider(provider: dict[str, Any], timeout: int) -> PlayerPageResult:
    name = str(provider.get("name", "UnknownProvider"))
    enabled = bool(provider.get("enabled", True))
    notes = str(provider.get("notes")) if provider.get("notes") else None
    if not enabled:
        return PlayerPageResult(name, False, True, "SKIPPED", None, None, None, None, None, [], [], None, notes)

    target_url, target_type = pick_target(provider)
    if not target_url:
        return PlayerPageResult(name, True, True, "PLAYER_PAGE_NOT_CONFIGURED", None, None, None, None, None, [], [], None, notes)

    status_code, body, elapsed_ms, error = http_get(target_url, provider, timeout)
    body_text = body.decode("utf-8", errors="ignore").lower()
    markers = [marker for marker in PLAYER_MARKERS if marker in body_text]
    blocked_keywords = [str(item) for item in provider.get("blockedKeywords", []) if str(item).strip()]
    blocked = [keyword for keyword in blocked_keywords if keyword.lower() in body_text]
    ok, status = classify(status_code, body, error, markers, blocked)

    return PlayerPageResult(name, True, ok, status, target_type, target_url, status_code, elapsed_ms, len(body), markers, blocked, error, notes)


def write_reports(payload: dict[str, Any], json_output: str, markdown_output: str) -> None:
    if json_output:
        path = Path(json_output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if markdown_output:
        path = Path(markdown_output)
        path.parent.mkdir(parents=True, exist_ok=True)
        lines = [
            "# Runtime Player Page Report",
            "",
            f"Generated: `{payload['generatedAt']}`",
            "",
            f"Total: **{payload['summary']['total']}**",
            f"OK: **{payload['summary']['ok']}**",
            f"Failed: **{payload['summary']['failed']}**",
            "",
            "> Player page health is not CloudStream playback proof.",
            "",
            "| Provider | Status | Type | HTTP | Bytes | Time | URL | Markers | Notes |",
            "|---|---:|---:|---:|---:|---:|---|---|---|",
        ]
        for item in payload["results"]:
            code = item["statusCode"] if item["statusCode"] is not None else ""
            size = item["responseBytes"] if item["responseBytes"] is not None else ""
            time_ms = f"{item['responseTimeMs']} ms" if item["responseTimeMs"] is not None else ""
            markers = ", ".join(item["matchedMarkers"])
            notes = item["notes"] or item["error"] or ""
            lines.append(f"| {item['name']} | {item['status']} | {item['targetType'] or ''} | {code} | {size} | {time_ms} | {item['url'] or ''} | {markers} | {notes} |")
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Run read-only runtime player page checks.")
    parser.add_argument("--config", default="healthcheck/runtime-providers.sample.json")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--json-output", default="")
    parser.add_argument("--markdown-output", default="")
    parser.add_argument("--fail-on-down", action="store_true")
    args = parser.parse_args()

    providers = load_providers(Path(args.config))
    results = [asdict(check_provider(provider, args.timeout)) for provider in providers]
    ok_count = sum(1 for item in results if item["ok"])
    failed_count = sum(1 for item in results if item["enabled"] and not item["ok"])
    payload = {"generatedAt": datetime.now(timezone.utc).isoformat(), "config": args.config, "summary": {"total": len(results), "ok": ok_count, "failed": failed_count}, "results": results}

    print(f"Checked runtime player pages: {len(results)}")
    print(f"OK: {ok_count}")
    print(f"Failed: {failed_count}")
    for item in results:
        print(f"- {item['name']}: {item['status']}")

    write_reports(payload, args.json_output, args.markdown_output)
    return 1 if args.fail_on_down and failed_count > 0 else 0


if __name__ == "__main__":
    raise SystemExit(main())
