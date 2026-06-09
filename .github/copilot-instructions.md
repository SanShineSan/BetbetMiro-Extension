# Copilot instructions for BetbetMiro-Extension

This repository contains CloudStream extension modules. Treat provider behavior, build safety, and repository metadata as the main quality gates.

## Operating priorities

1. Keep provider behavior working.
2. Keep Gradle builds green.
3. Keep `repo.json` and generated plugin metadata valid.
4. Keep GitHub Actions safe and reviewable.
5. Improve documentation only when it does not interfere with provider work.
6. Cosmetics come last.

## Safety rules

- Do not modify unrelated providers.
- Do not touch `.github/ISSUE_TEMPLATE/*` or `.github/pull_request_template.md` unless the task explicitly asks for template work.
- Do not refactor, reformat, or beautify files outside the requested scope.
- Do not add fallback hosts, extractors, selectors, parsers, categories, or domains without evidence from the active source or provided logs/HAR/screenshots.
- Do not claim a build, playback, extractor, callback, repository metadata, GitHub Actions, commit, or pull request succeeded unless it was actually verified.
- Do not auto-merge or bypass human review.

## Provider maintenance workflow

For provider fixes or new providers, work evidence-first:

1. Identify the provider/module and exact issue area.
2. Collect or request evidence for homepage/category/search/detail/episode/player/API/media/subtitle flow.
3. Compare the active source behavior with the current parser.
4. Identify the exact root cause.
5. Patch only the requested or proven-broken flow.
6. Bump the changed provider version once in its `build.gradle.kts`.
7. Validate honestly and report what was not verified.

Provider success is app-behavior based, not parser-theory based:

- homepage/mainPage categories should show populated cards with title, poster, and correct detail URL;
- load/detail should open without crash and produce episodes or a playable movie item;
- loadLinks/playback should target callback video link count greater than zero with correct headers/referer/origin/cookie when required by the source.

If runtime playback is not proven, use this wording:

`Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source.`

If HAR was used as the main source, use:

`Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source/HAR.`

## Build and validation

Prefer lightweight checks first:

```bash
python scripts/count_providers.py
python scripts/validate_metadata.py
```

Run Gradle only when the task requires build validation or provider code was modified. Do not describe Kotlin syntax sanity as a Gradle build success.

## Reporting format

When reporting a completed change, include:

- changed files;
- root cause, if a provider was fixed;
- version bump, if provider code changed;
- local build status;
- `repo.json` status;
- `plugins.json` status;
- GitHub Actions status;
- homepage/load/playback runtime status when applicable;
- file-scope confirmation;
- commit SHA or PR link only if actually created.
