# Documentation Index

This directory contains maintenance and compatibility documentation for **BetbetMiro Extension**.

Use this page as a quick map for repository contributors, issue reporters, and maintainers.

---

## Provider Maintenance

- [`PROVIDER_MAINTENANCE.md`](PROVIDER_MAINTENANCE.md)

Use this guide for provider fixes, new providers, version bump rules, source evidence, CloudStream behavior validation, and honest status reporting.

Recommended for:

- Provider fixes.
- New provider development.
- Domain migration review.
- Playback resolver validation.
- Pull Request review.

---

## New Provider Guide

- [`NEW_PROVIDER_GUIDE.md`](NEW_PROVIDER_GUIDE.md)

Use this guide before adding a new CloudStream provider.

Recommended for:

- New provider planning.
- Source evidence collection.
- Homepage/search/load/loadLinks implementation checks.
- Initial provider version and build metadata review.
- Avoiding unfinished skeleton providers.

---

## Validation Checklist

- [`VALIDATION_CHECKLIST.md`](VALIDATION_CHECKLIST.md)

Use this checklist before claiming that provider behavior, build status, repository metadata, or release readiness is fixed.

Recommended for:

- Homepage/mainPage validation.
- Category, search, and detail validation.
- Playback/loadLinks callback validation.
- Provider version bump checks.
- Final status reporting.

---

## Build Guide

- [`BUILD_GUIDE.md`](BUILD_GUIDE.md)

Use this guide when running or reporting local Gradle builds on Linux, macOS, Termux, or Windows.

Recommended for:

- Local build setup.
- Java and Gradle wrapper checks.
- Common build failure triage.
- Build status reporting.
- Distinguishing local Gradle and GitHub Actions results.

---

## GitHub Actions Guide

- [`ACTIONS_GUIDE.md`](ACTIONS_GUIDE.md)

Use this guide when reading workflow runs, job logs, artifacts, and remote build/publish status.

Recommended for:

- GitHub Actions status reporting.
- Failed workflow triage.
- Artifact validation.
- `repo.json` and `plugins.json` workflow checks.
- Avoiding false runtime claims from green workflows.

---

## Repository Metadata Guide

- [`REPO_METADATA_GUIDE.md`](REPO_METADATA_GUIDE.md)

Use this guide when checking `repo.json`, `plugins.json`, provider metadata, generated artifacts, or install/publish metadata.

Recommended for:

- Repository install metadata review.
- Plugin list validation.
- Provider `build.gradle.kts` metadata checks.
- Artifact and URL validation.
- Avoiding unsupported metadata claims.

---

## Commit Guide

- [`COMMIT_GUIDE.md`](COMMIT_GUIDE.md)

Use this guide before writing commit messages for provider fixes, new providers, documentation, build, metadata, or workflow changes.

Recommended for:

- Provider fix commit bodies.
- Version bump wording.
- Evidence-backed commit summaries.
- Upstream/reference credit wording.
- Avoiding build/playback/metadata overclaims.

---

## Troubleshooting

- [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md)

Use this guide when diagnosing build failures, GitHub Actions failures, repository metadata problems, provider homepage/detail/playback issues, domain changes, and network/region issues.

Recommended for:

- Debugging before patching.
- Provider issue triage.
- Build or workflow failure analysis.
- Honest status reporting.

---

## Compatibility

- [`COMPATIBILITY.md`](COMPATIBILITY.md)

Use this guide to understand CloudStream compatibility, stable vs prerelease behavior, Gradle/Java notes, repository metadata, GitHub Actions, and runtime validation boundaries.

Recommended for:

- Build issues.
- CloudStream app compatibility questions.
- Runtime/device behavior reports.
- `repo.json` and `plugins.json` impact review.

---

## Release Process

- [`RELEASE_PROCESS.md`](RELEASE_PROCESS.md)

Use this guide before publishing, announcing, or reviewing a release/build output.

Recommended for:

- Release readiness checks.
- Local Gradle build notes.
- `repo.json` and `plugins.json` validation notes.
- GitHub Actions result reporting.
- Provider version bump checks.
- Documentation-only release wording.
- Manual ZIP/no-commit task handling.

---

## Domain Check Tool

- [`DOMAIN_CHECK.md`](DOMAIN_CHECK.md)

Use this guide before running or reviewing `DomainCheck.py`.

Recommended for:

- Source domain migration.
- `mainUrl` redirect checks.
- Reviewing automated domain update diffs.
- Avoiding false claims that a domain update proves provider playback.

---

## Repository-Level Documents

These files live in the repository root:

- [`../README.md`](../README.md) — Indonesian main README.
- [`../README_EN.md`](../README_EN.md) — English README.
- [`../CONTRIBUTING.md`](../CONTRIBUTING.md) — contribution guide.
- [`../SUPPORT.md`](../SUPPORT.md) — support guide.
- [`../SECURITY.md`](../SECURITY.md) — security policy.
- [`../CODE_OF_CONDUCT.md`](../CODE_OF_CONDUCT.md) — community rules.
- [`../CHANGELOG.md`](../CHANGELOG.md) — changelog.
- [`../CREDITS.md`](../CREDITS.md) — credits and attribution guide.

---

## GitHub Templates

These files live under `.github/`:

- [`../.github/pull_request_template.md`](../.github/pull_request_template.md) — Pull Request checklist.
- [`../.github/ISSUE_TEMPLATE/provider_broken.yml`](../.github/ISSUE_TEMPLATE/provider_broken.yml) — broken provider report.
- [`../.github/ISSUE_TEMPLATE/provider_request.yml`](../.github/ISSUE_TEMPLATE/provider_request.yml) — new provider request.
- [`../.github/ISSUE_TEMPLATE/bug_report.yml`](../.github/ISSUE_TEMPLATE/bug_report.yml) — repository/build/workflow bug report.

---

## Maintainer Rule

For provider work, prioritize evidence and CloudStream app behavior over theory.

A good provider fix should clearly state:

```text
Homepage category cards: proven / not proven
Load detail/episode: proven / not proven
Playback callback link > 0: proven / not proven
Gradle build: SUCCESS / failed / not run
File scope: related files only / unrelated files touched
```

Do not claim a status that was not actually verified.
