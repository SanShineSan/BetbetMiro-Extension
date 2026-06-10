#!/usr/bin/env python3
"""Build a simple health summary from inventory and optional reports."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def load_json(path: str) -> Any | None:
    if not path:
        return None
    p = Path(path)
    if not p.is_file():
        return None
    return json.loads(p.read_text(encoding="utf-8"))


def load_inventory(path: str) -> list[dict[str, Any]]:
    data = load_json(path)
    if not isinstance(data, dict):
        return []
    modules = data.get("modules")
    return modules if isinstance(modules, list) else []


def load_report(path: str) -> dict[str, str]:
    data = load_json(path)
    out: dict[str, str] = {}
    if not isinstance(data, dict):
        return out
    results = data.get("results")
    if not isinstance(results, list):
        return out
    for item in results:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name") or item.get("module") or "")
        status = str(item.get("status") or "UNKNOWN")
        if name:
            out[name] = status
    return out


def pick_name(module: dict[str, Any]) -> str:
    names = module.get("names")
    if isinstance(names, list) and names:
        return str(names[0])
    return str(module.get("module") or "UnknownProvider")


def classify(values: list[str]) -> str:
    known = [value for value in values if value and value != "UNKNOWN"]
    if not known:
        return "UNKNOWN"
    bad_words = ("ERROR", "TIMEOUT", "NOT_FOUND", "SERVER_ERROR", "BLOCKED", "EMPTY")
    if any(any(word in value for word in bad_words) for value in known):
        return "BROKEN"
    warn_words = ("NOT_CONFIGURED", "SKIPPED")
    if any(any(word in value for word in warn_words) for value in known):
        return "DEGRADED"
    return "GOOD"


def write_markdown(path: Path, payload: dict[str, Any]) -> None:
    lines = [
        "# Provider Health Summary",
        "",
        f"Generated: `{payload['generatedAt']}`",
        "",
        f"Providers: **{payload['providerCount']}**",
        "",
        "| Provider | Module | Overall | Domain | Search | Detail | Player | Media | Headers |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for item in payload["providers"]:
        lines.append(
            f"| {item['name']} | {item['module']} | {item['overall']} | {item['domain']} | {item['search']} | {item['detail']} | {item['player']} | {item['media']} | {item['headers']} |"
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build provider health summary.")
    parser.add_argument("--inventory", default="provider-inventory.json")
    parser.add_argument("--domain-report", default="")
    parser.add_argument("--search-report", default="")
    parser.add_argument("--detail-report", default="")
    parser.add_argument("--player-report", default="")
    parser.add_argument("--media-report", default="")
    parser.add_argument("--headers-report", default="")
    parser.add_argument("--json-output", default="provider-health-summary.json")
    parser.add_argument("--markdown-output", default="provider-health-summary.md")
    args = parser.parse_args()

    reports = {
        "domain": load_report(args.domain_report),
        "search": load_report(args.search_report),
        "detail": load_report(args.detail_report),
        "player": load_report(args.player_report),
        "media": load_report(args.media_report),
        "headers": load_report(args.headers_report),
    }

    providers = []
    for module in load_inventory(args.inventory):
        module_name = str(module.get("module") or "")
        name = pick_name(module)
        values = {
            key: report.get(name) or report.get(module_name) or "UNKNOWN"
            for key, report in reports.items()
        }
        providers.append({
            "name": name,
            "module": module_name,
            **values,
            "overall": classify(list(values.values())),
        })

    providers.sort(key=lambda item: (item["overall"], item["module"].lower()))
    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "providerCount": len(providers),
        "providers": providers,
    }

    print(f"Provider health entries: {len(providers)}")
    out_json = Path(args.json_output)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    write_markdown(Path(args.markdown_output), payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
