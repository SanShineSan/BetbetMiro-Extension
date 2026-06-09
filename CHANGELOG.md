# Changelog

All notable changes to **BetbetMiro Extension** should be documented in this file.

This project does not guarantee that every historical provider change before this changelog was tracked here. New maintenance work should be recorded from this point forward.

The format is inspired by Keep a Changelog, with practical adjustments for CloudStream provider maintenance.

---

## [Unreleased]

Use this section for changes that have been committed but not yet considered part of a published plugin/repository update.

### Added

- Add new provider entries here.
- Add new documentation, workflow, or tooling entries here.

### Changed

- Add provider parser, domain, category, search, detail, or playback behavior updates here.

### Fixed

- Add provider fixes here, including homepage/load/playback fixes.

### Removed

- Add removed providers, dead domains, or deprecated workflows here.

### Validation

- Record Gradle build status, repo/plugin metadata checks, and runtime playback notes when available.

---

## Changelog Entry Template

Use this template for future provider changes:

```markdown
## YYYY-MM-DD

### Fixed

- `ProviderName`: short fix summary.
  - Root cause: ...
  - Changed files: ...
  - Version bump: old -> new
  - Homepage category cards: proven / not proven
  - Load detail/episode: proven / not proven
  - Playback callback link > 0: proven / not proven
  - Gradle build: SUCCESS / failed / not run
  - Notes: ...
```

---

## Maintenance Notes

When updating this changelog, keep entries clear and honest:

- Do not claim playback success without video callback link evidence.
- Do not claim Gradle build success unless Gradle actually completed successfully.
- Do not claim `repo.json` or `plugins.json` validity without checking them.
- Mention unverified runtime/device behavior explicitly.
- Keep provider-specific changes grouped by provider name.
- Keep documentation-only changes separate from provider runtime fixes.

Recommended runtime wording when playback is not directly proven:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source.
```

Or when HAR/log evidence was used:

```text
Playback belum terbukti di runtime/device, tetapi resolver sudah disesuaikan dengan evidence source/HAR.
```

---

## Documentation / Repository Maintenance History

### 2026-06-09

### Added

- `CONTRIBUTING.md`: contribution guidelines for provider fixes, new providers, version bump rules, build validation, and scope safety.
- `.github/ISSUE_TEMPLATE/provider_broken.yml`: structured issue form for broken provider reports.
- `SUPPORT.md`: support guidelines for provider issues, build issues, contribution help, and repository support boundaries.
- `docs/PROVIDER_MAINTENANCE.md`: public provider maintenance guide covering evidence-based fixes, CloudStream behavior validation, domain changes, `DomainCheck.py`, and honest status labels.
- `.editorconfig`: repository formatting rules for Kotlin, KTS, Java, XML, Gradle, YAML, JSON, Markdown, shell wrapper, and Windows batch files.

### Changed

- `.github/pull_request_template.md`: improved Pull Request checklist for provider evidence, CloudStream behavior, playback validation, build status, and scope safety.
- `.gitignore`: improved ignore rules for IDE files, Gradle caches, Android generated files, Python cache, logs, OS files, and local build artifacts.
- `README.md` and `README_EN.md`: added language switchers between Indonesian and English documentation.
- `settings.gradle.kts`: renamed root project to `BetbetMiro-Extension`.
- `.vscode/settings.json`: improved editor and logcat settings.
- `.vscode/extensions.json`: added recommended development extensions.

### Validation

- Gradle build local: not run for documentation/editor/gitignore-only changes.
- Provider runtime: not affected by these documentation/editor/gitignore-only changes.
- `repo.json`: not changed by these documentation/editor/gitignore-only changes.
- `plugins.json`: not changed by these documentation/editor/gitignore-only changes.
