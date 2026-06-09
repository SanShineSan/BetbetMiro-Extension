#!/usr/bin/env python3
"""Read-only provider domain health checker.

This script checks configured provider base URLs and writes optional JSON and
Markdown reports. It does not modify provider files, metadata, or repository
state.
"""

from __future__ import annotations

import argparse
import json
import socket
import ssl
import sys
import time
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from http.client import HTTPSConnection, HTTPConnection, HTTPResponse
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

DEFAULT_TIMEOUT_SECONDS = 12
DEFAULT_USER_AGENT = "BetbetMiro-HealthCheck/1.0"


@dataclass
class HealthResult:
    name: str
    mainUrl: str
    enabled: bool
    ok: bool
    status: str
    statusCode: int | None
    finalUrl: str | None
    redirect: bool
    responseTimeMs: int | None
    error: str | None
    notes: str | None


def load_config(path: Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as file:
        data = json.load(file)

    if isinstance(data, dict):
        providers = data.get("providers")
    else:
        providers = data

    if not isinstance(providers, list):
        raise ValueError("Health check config must be a JSON object with a providers array or a JSON array")

    normalized: list[dict[str, Any]] = []
    for index, item in enumerate(providers):
        if not isinstance(item, dict):
            raise ValueError(f"providers[{index}] must be an object")
        if not item.get("name") or not item.get("mainUrl"):
            raise ValueError(f"providers[{index}] must include name and mainUrl")
        normalized.append(item)

    return normalized


def classify_status(status_code: int | None, error: str | None, redirect: bool) -> str:
    if error:
        if "timed out" in error.lower() or "timeout" in error.lower():
            return "TIMEOUT"
        return "ERROR"
    if status_code is None:
        return "ERROR"
    if redirect:
        return "REDIRECT"
    if 200 <= status_code <= 299:
        return "UP"
    if status_code == 403:
        return "FORBIDDEN"
    if status_code == 404:
        return "NOT_FOUND"
    if 500 <= status_code <= 599:
        return "SERVER_ERROR"
    return "HTTP_" + str(status_code)


def request_once(url: str, timeout: int, user_agent: str) -> tuple[int | None, str | None, bool, int | None, str | None]:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        return None, None, False, None, f"unsupported URL scheme: {parsed.scheme}"
    if not parsed.netloc:
        return None, None, False, None, "missing host"

    path = parsed.path or "/"
    if parsed.query:
        path += "?" + parsed.query

    connection_class = HTTPSConnection if parsed.scheme == "https" else HTTPConnection
    start = time.monotonic()
    connection: HTTPSConnection | HTTPConnection | None = None

    try:
        connection = connection_class(parsed.netloc, timeout=timeout)
        connection.request(
            "GET",
            path,
            headers={
                "User-Agent": user_agent,
                "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            },
        )
        response: HTTPResponse = connection.getresponse()
        status_code = response.status
        location = response.getheader("Location")
        response.read(1024)
        elapsed_ms = int((time.monotonic() - start) * 1000)

        redirect = status_code in {301, 302, 303, 307, 308}
        final_url = location if redirect and location else url
        return status_code, final_url, redirect, elapsed_ms, None
    except (socket.timeout, TimeoutError) as exc:
        return None, None, False, None, f"timeout: {exc}"
    except (OSError, ssl.SSLError) as exc:
        return None, None, False, None, str(exc)
    finally:
        if connection is not None:
            connection.close()


def check_provider(provider: dict[str, Any], timeout: int, user_agent: str) -> HealthResult:
    name = str(provider["name"])
    main_url = str(provider["mainUrl"])
    enabled = bool(provider.get("enabled", True))
    notes = provider.get("notes")
    expected_status = provider.get("expectedStatus", [200, 301, 302, 403])

    if not enabled:
        return HealthResult(
            name=name,
            mainUrl=main_url,
            enabled=False,
            ok=True,
            status="SKIPPED",
            statusCode=None,
            finalUrl=None,
            redirect=False,
            responseTimeMs=None,
            error=None,
            notes=str(notes) if notes is not None else None,
        )

    status_code, final_url, redirect, elapsed_ms, error = request_once(main_url, timeout, user_agent)
    status = classify_status(status_code, error, redirect)

    ok = False
    if status_code is not None and isinstance(expected_status, list):
        ok = status_code in expected_status
    if redirect and status_code in {301, 302, 303, 307, 308}:
        ok = True

    return HealthResult(
        name=name,
        mainUrl=main_url,
        enabled=True,
        ok=ok,
        status=status,
        statusCode=status_code,
        finalUrl=final_url,
        redirect=redirect,
        responseTimeMs=elapsed_ms,
        error=error,
        notes=str(notes) if notes is not None else None,
    )


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_markdown(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rows = [
        "# Provider Domain Health Report",
        "",
        f"Generated: `{payload['generatedAt']}`",
        "",
        f"Total: **{payload['summary']['total']}**",
        f"OK: **{payload['summary']['ok']}**",
        f"Failed: **{payload['summary']['failed']}**",
        "",
        "> Domain health is not CloudStream playback proof. Homepage, detail, and playback status must still be validated separately.",
        "",
        "| Provider | Status | HTTP | Redirect | Response time | Final URL | Notes |",
        "|---|---:|---:|---:|---:|---|---|",
    ]

    for item in payload["results"]:
        status_code = item["statusCode"] if item["statusCode"] is not None else ""
        response_time = f"{item['responseTimeMs']} ms" if item["responseTimeMs"] is not None else ""
        redirect = "yes" if item["redirect"] else "no"
        final_url = item["finalUrl"] or ""
        notes = item["notes"] or item["error"] or ""
        rows.append(
            f"| {item['name']} | {item['status']} | {status_code} | {redirect} | {response_time} | {final_url} | {notes} |"
        )

    path.write_text("\n".join(rows) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Run read-only provider domain health checks.")
    parser.add_argument("--config", default="healthcheck/providers.example.json", help="Health check config path.")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_SECONDS, help="Request timeout in seconds.")
    parser.add_argument("--user-agent", default=DEFAULT_USER_AGENT, help="User-Agent header.")
    parser.add_argument("--json-output", default="", help="Optional JSON report output path.")
    parser.add_argument("--markdown-output", default="", help="Optional Markdown report output path.")
    parser.add_argument("--fail-on-down", action="store_true", help="Exit 1 when an enabled provider is not OK.")
    args = parser.parse_args()

    config_path = Path(args.config)
    try:
        providers = load_config(config_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Failed to load health check config: {exc}", file=sys.stderr)
        return 2

    results = [asdict(check_provider(provider, args.timeout, args.user_agent)) for provider in providers]
    ok_count = sum(1 for item in results if item["ok"])
    failed_count = sum(1 for item in results if item["enabled"] and not item["ok"])

    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "config": str(config_path),
        "summary": {
            "total": len(results),
            "ok": ok_count,
            "failed": failed_count,
        },
        "results": results,
    }

    print(f"Checked providers: {len(results)}")
    print(f"OK: {ok_count}")
    print(f"Failed: {failed_count}")
    for item in results:
        code = item["statusCode"] if item["statusCode"] is not None else ""
        print(f"- {item['name']}: {item['status']} {code}")

    if args.json_output:
        write_json(Path(args.json_output), payload)
    if args.markdown_output:
        write_markdown(Path(args.markdown_output), payload)

    if args.fail_on_down and failed_count > 0:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
