#!/usr/bin/env python3
"""Read-only media candidate scanner.

This script scans explicit runtime sample pages for visible iframe/embed/API/media
candidate strings. Found candidates are not CloudStream callback proof.
"""

from __future__ import annotations

import argparse
import json
import re
import socket
import ssl
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from http.client import HTTPConnection, HTTPSConnection, HTTPResponse
from pathlib import Path
from typing import Any
from urllib.parse import urljoin, urlparse

DEFAULT_TIMEOUT_SECONDS = 15
DEFAULT_USER_AGENT = "BetbetMiro-RuntimeHealthCheck/1.0"
URL_RE = re.compile(r"https?://[^\s'\"<>]+")
IFRAME_RE = re.compile(r"<iframe[^>]+src=[\"']([^\"']+)[\"']", re.IGNORECASE)
SOURCE_RE = re.compile(r"<source[^>]+src=[\"']([^\"']+)[\"']", re.IGNORECASE)


@dataclass
class MediaCandidateResult:
    name: str
    enabled: bool
    ok: bool
    status: str
    url: str | None
    statusCode: int | None
    responseTimeMs: int | None
    candidateCount: int
    candidates: list[str]
    matchedPatterns: list[str]
    error: str | None
    notes: str | None


def load_providers(path: Path) -> list[dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    providers = data.get("providers") if isinstance(data, dict) else data
    if not isinstance(providers, list):
        raise ValueError("Config must contain providers array or be an array")
    return providers


def pick_url(provider: dict[str, Any]) -> str | None:
    for key in ("playerUrl", "episodeUrl", "detailUrl"):
        value = str(provider.get(key, "")).strip()
        if value:
            return value
    return None


def http_get(url: str, provider: dict[str, Any], timeout: int) -> tuple[int | None, str, int | None, str | None]:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        return None, "", None, f"unsupported URL scheme: {parsed.scheme}"
    if not parsed.netloc:
        return None, "", None, "missing host"

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
        body = response.read(512_000).decode("utf-8", errors="ignore")
        elapsed_ms = int((time.monotonic() - start) * 1000)
        return response.status, body, elapsed_ms, None
    except (socket.timeout, TimeoutError) as exc:
        return None, "", None, f"timeout: {exc}"
    except (OSError, ssl.SSLError) as exc:
        return None, "", None, str(exc)
    finally:
        if connection is not None:
            connection.close()


def extract_candidates(base_url: str, body: str, patterns: list[str]) -> tuple[list[str], list[str]]:
    candidates: set[str] = set()
    matched_patterns: set[str] = set()

    for match in URL_RE.findall(body):
        if any(pattern.lower() in match.lower() for pattern in patterns):
            candidates.add(match.rstrip(".,);"))
    for regex in (IFRAME_RE, SOURCE_RE):
        for match in regex.findall(body):
            normalized = urljoin(base_url, match)
            candidates.add(normalized)

    lower_body = body.lower()
    for pattern in patterns:
        if pattern.lower() in lower_body:
            matched_patterns.add(pattern)

    return sorted(candidates)[:50], sorted(matched_patterns)


def check_provider(provider: dict[str, Any], timeout: int) -> MediaCandidateResult:
    name = str(provider.get("name", "UnknownProvider"))
    enabled = bool(provider.get("enabled", True))
    notes = str(provider.get("notes")) if provider.get("notes") else None
    if not enabled:
        return MediaCandidateResult(name, False, True, "SKIPPED", None, None, None, 0, [], [], None, notes)

    url = pick_url(provider)
    if not url:
        return MediaCandidateResult(name, True, True, "MEDIA_CHECK_NOT_CONFIGURED", None, None, None, 0, [], [], None, notes)

    patterns = [str(item) for item in provider.get("expectedMediaPatterns", [".m3u8", ".mp4", "iframe", "embed", "source"]) if str(item).strip()]
    status_code, body, elapsed_ms, error = http_get(url, provider, timeout)
    if error:
        status = "MEDIA_CANDIDATE_TIMEOUT" if "timeout" in error.lower() else "MEDIA_CANDIDATE_ERROR"
        return MediaCandidateResult(name, True, False, status, url, status_code, elapsed_ms, 0, [], [], error, notes)
    if status_code is None or not (200 <= status_code <= 399):
        return MediaCandidateResult(name, True, False, f"MEDIA_CANDIDATE_HTTP_{status_code}", url, status_code, elapsed_ms, 0, [], [], None, notes)

    candidates, matched_patterns = extract_candidates(url, body, patterns)
    if candidates:
        status = "MEDIA_CANDIDATE_FOUND"
        ok = True
    elif matched_patterns:
        status = "MEDIA_PATTERN_FOUND"
        ok = True
    else:
        status = "MEDIA_CANDIDATE_NOT_FOUND"
        ok = False

    return MediaCandidateResult(name, True, ok, status, url, status_code, elapsed_ms, len(candidates), candidates, matched_patterns, None, notes)


def write_reports(payload: dict[str, Any], json_output: str, markdown_output: str) -> None:
    if json_output:
        path = Path(json_output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if markdown_output:
        path = Path(markdown_output)
        path.parent.mkdir(parents=True, exist_ok=True)
        lines = [
            "# Runtime Media Candidate Report",
            "",
            f"Generated: `{payload['generatedAt']}`",
            "",
            f"Total: **{payload['summary']['total']}**",
            f"OK: **{payload['summary']['ok']}**",
            f"Failed: **{payload['summary']['failed']}**",
            "",
            "> Media candidates are not CloudStream callback proof.",
            "",
            "| Provider | Status | HTTP | Candidates | Patterns | URL | Notes |",
            "|---|---:|---:|---:|---|---|---|",
        ]
        for item in payload["results"]:
            code = item["statusCode"] if item["statusCode"] is not None else ""
            patterns = ", ".join(item["matchedPatterns"])
            notes = item["notes"] or item["error"] or ""
            lines.append(f"| {item['name']} | {item['status']} | {code} | {item['candidateCount']} | {patterns} | {item['url'] or ''} | {notes} |")
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Run read-only media candidate checks.")
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

    print(f"Checked media candidates: {len(results)}")
    print(f"OK: {ok_count}")
    print(f"Failed: {failed_count}")
    for item in results:
        print(f"- {item['name']}: {item['status']} ({item['candidateCount']} candidates)")

    write_reports(payload, args.json_output, args.markdown_output)
    return 1 if args.fail_on_down and failed_count > 0 else 0


if __name__ == "__main__":
    raise SystemExit(main())
