# Contributing to BetbetMiro Extension

Thank you for your interest in contributing to **BetbetMiro Extension**.

This repository contains CloudStream providers and supporting maintenance files. Contributions are welcome, but provider changes must be focused, evidence-based, and safe for the repository.

---

## Contribution Priorities

The repository follows this priority order:

1. Keep providers alive and usable.
2. Keep Gradle builds clean.
3. Keep `repo.json` and `plugins.json` valid.
4. Keep GitHub Actions working.
5. Improve documentation.
6. Apply cosmetic changes only when they do not break provider behavior.

Provider functionality is more important than cosmetic cleanup.

---

## Before Opening a Pull Request

Before opening a Pull Request, please make sure your change is necessary and focused.

For provider fixes, include evidence from the active source website whenever possible:

- Active domain.
- Homepage or category page sample.
- Search result sample, if search is supported.
- Detail page sample.
- Episode list or movie play item sample.
- Player page, iframe, API, media-player script, or direct media URL evidence.
- Required headers such as referer, origin, cookies, or user agent when needed.

Avoid guessing. A parser should follow the active source behavior.

---

## Provider Fix Standard

A provider fix should cover the actual broken flow instead of only hiding the error.

A proper fix should check:

- `getMainPage()` returns populated categories/cards when the source supports it.
- Cards have usable title, poster when available, and correct detail URL.
- `load()` opens detail data without crashing.
- Series or anime entries expose valid episode data.
- Movie entries expose a valid playable item.
- `loadLinks()` emits at least one valid video callback link when playback is working.
- Headers and referers follow the active source/player behavior.

Do not treat `return true` in `loadLinks()` as proof of playback. The resolver must emit a video callback link.

---

## Version Bump Rule

Every provider code change must bump the provider version in its `build.gradle.kts` file.

Example:

```kotlin
version = 12
```

If provider code changes but the version is not bumped, the Pull Request may be rejected or requested for revision.

Documentation-only changes do not need provider version bumps.

---

## File Scope Rule

Keep changes focused.

Do not modify unrelated providers, unrelated Gradle files, or formatting across the repository unless the Pull Request is specifically about that scope.

Avoid large beautify-only changes. They make provider fixes harder to review.

---

## New Provider Requirements

A new provider should not be only a skeleton.

A new provider should include:

- Working metadata in `build.gradle.kts`.
- Main provider class and plugin registration.
- Homepage or category support when available.
- Search support when the source supports search.
- Detail loading.
- Episode list or movie play item.
- Playback resolver that emits video callback links.
- Source-backed headers when needed.

If playback cannot be fully verified, clearly state what was verified and what remains unverified.

---

## Build and Validation

When possible, run:

```bash
./gradlew make
```

For Windows:

```bat
.\gradlew.bat make
```

If you cannot run a build, clearly mention it in the Pull Request.

Do not claim that the build is green unless Gradle actually succeeded.

---

## Recommended Pull Request Checklist

Use this checklist before submitting:

- [ ] The change is focused on one provider or one clear maintenance task.
- [ ] The root cause is explained.
- [ ] Active source evidence was checked.
- [ ] Provider version was bumped when provider code changed.
- [ ] Homepage/category behavior was checked when applicable.
- [ ] Detail loading was checked.
- [ ] Playback/loadLinks behavior was checked when applicable.
- [ ] Gradle build was run, or the reason it was not run is stated.
- [ ] `repo.json` and `plugins.json` impact was considered.
- [ ] Unrelated files were not changed.

---

## Issue Reports

When reporting a provider issue, please include:

- Provider name.
- Problematic URL.
- Screenshot or screen recording when possible.
- CloudStream version.
- CloudStream stable or prerelease.
- Error log, if available.
- Steps to reproduce.

Incomplete reports may be hard to investigate.

---

## Adult Content Notice

Some providers may access adult/NSFW content.

Contributors and users are responsible for following the laws and age requirements in their own region.

Do not submit content, screenshots, or examples that violate GitHub rules or applicable law.

---

## Maintainer Notes

The maintainer may request changes, reject broad or risky Pull Requests, or ask for additional evidence before merging provider fixes.

The goal is to keep the repository useful, buildable, and stable for CloudStream users.
