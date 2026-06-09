#!/usr/bin/env python3
"""Extract provider mainUrl candidates from Kotlin sources.

This script is read-only. It scans top-level module folders, finds Kotlin files,
and extracts simple `override var mainUrl` or `override val mainUrl` assignments.
It is intended to help build health-check config drafts, not to modify providers.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

EXCLUDED_DIRS = {
    ".git",
    ".github",
    ".gradle",
    ".idea",
    ".vscode",
    "build",
    "builds",
    "docs",
    "gradle",
    "healthcheck",
    "scripts",
    "templates",
}

MAIN_URL_RE = re.compile(r"override\s+(?:var|val)\s+mainUrl\s*=\s*\"([^\"]+)\"")
NAME_RE = re.compile(r"override\s+val\s+name\s*=\s*\"([^\"]+)\"")
CLASS_RE = re.compile(r"class\s+([A-Za-z0-9_]+)")


def module_dirs(root: Path) -> list[Path]:
    modules: list[Path] = []
    for child in sorted(root.iterdir(), key=lambda item: item.name.lower()):
        if not child.is_dir() or child.name in EXCLUDED_DIRS:
            continue
        if (child / "build.gradle.kts").is_file():
            modules.append(child)
    return modules


def extract_from_file(path: Path, module: str, root: Path) -> list[dict[str, Any]]:
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        text = path.read_text(encoding="utf-8", errors="ignore")

    main_urls = MAIN_URL_RE.findall(text)
    if not main_urls:
        return []

    name_match = NAME_RE.search(text)
    class_match = CLASS_RE.search(text)
    provider_name = name_match.group(1) if name_match else (class_match.group(1) if class_match else module)

    records: list[dict[str, Any]] = []
    for main_url in sorted(set(main_urls)):
        records.append(
            {
                "name": provider_name,
                "module": module,
                "mainUrl": main_url,
                "enabled": True,
                "sourceFile": str(path.relative_to(root)),
                "expectedStatus": [200, 301, 302, 403],
                "expectedKeywords": [],
                "notes": "Generated candidate. Review before using in production healthcheck config.",
            }
        )
    return records


def extract(root: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for module in module_dirs(root):
        for kotlin_file in sorted(module.rglob("*.kt")):
            if "build" in kotlin_file.parts:
                continue
            records.extend(extract_from_file(kotlin_file, module.name, root))

    deduped: dict[tuple[str, str, str], dict[str, Any]] = {}
    for record in records:
        key = (record["module"], record["name"], record["mainUrl"])
        deduped[key] = record
    return sorted(deduped.values(), key=lambda item: (item["module"].lower(), item["name"].lower(), item["mainUrl"]))


def main() -> int:
    parser = argparse.ArgumentParser(description="Extract provider mainUrl candidates from Kotlin sources.")
    parser.add_argument("--root", default=".", help="Repository root path. Default: current directory.")
    parser.add_argument("--json-output", default="", help="Optional JSON output path.")
    parser.add_argument("--markdown-output", default="", help="Optional Markdown output path.")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    providers = extract(root)
    payload = {"providers": providers, "count": len(providers)}

    print(f"Extracted provider mainUrl candidates: {len(providers)}")
    for item in providers:
        print(f"- {item['module']} / {item['name']}: {item['mainUrl']}")

    if args.json_output:
        output = Path(args.json_output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    if args.markdown_output:
        output = Path(args.markdown_output)
        output.parent.mkdir(parents=True, exist_ok=True)
        lines = [
            "# Extracted Provider mainUrl Candidates",
            "",
            f"Total: **{len(providers)}**",
            "",
            "| Module | Provider | mainUrl | Source file |",
            "|---|---|---|---|",
        ]
        for item in providers:
            lines.append(f"| {item['module']} | {item['name']} | {item['mainUrl']} | {item['sourceFile']} |")
        output.write_text("\n".join(lines) + "\n", encoding="utf-8")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
