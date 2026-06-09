## Summary

Describe the main change in this Pull Request.

- Target provider/module:
- Root cause:
- Fix approach:

---

## Change Type

- [ ] Provider fix
- [ ] New provider
- [ ] Remove broken/dead provider
- [ ] Category/genre update
- [ ] Search/homepage fix
- [ ] Detail/load fix
- [ ] Playback/loadLinks fix
- [ ] Build/compile fix
- [ ] Repository/workflow maintenance
- [ ] Documentation only
- [ ] Small refactor

---

## Provider / Module Changed

List every provider/module/file group changed by this PR.

- 

---

## Evidence Checklist

For provider changes, include source evidence whenever possible.

- [ ] Active source domain was checked.
- [ ] Homepage/main page was checked.
- [ ] Category page was checked, if supported.
- [ ] Search was checked, if supported.
- [ ] Detail page was checked.
- [ ] Episode list or movie play item was checked.
- [ ] Player page, iframe, API, media-player script, or direct media URL was checked.
- [ ] Required headers/referer/origin/cookies were checked, if needed.

Evidence notes:

```text
Paste source URLs, logs, HAR notes, or short findings here.
```

---

## CloudStream Behavior Checklist

- [ ] Homepage/category cards are populated.
- [ ] Cards have usable title.
- [ ] Cards have poster when available.
- [ ] Cards open the correct detail URL.
- [ ] Detail page loads without crashing.
- [ ] Series/anime entries expose episode data, if applicable.
- [ ] Movie entries expose a playable item, if applicable.
- [ ] `loadLinks()` emits at least one video callback link when playback is working.
- [ ] `loadLinks()` does not send empty or relative URLs to extractors.

Playback status:

- [ ] Playback callback link > 0 verified.
- [ ] Playback not verified; reason explained below.
- [ ] Not applicable for this PR.

Playback notes:

```text
Explain playback result or why it was not verified.
```

---

## Build / Validation

- [ ] Provider `build.gradle.kts` version was bumped when provider code changed.
- [ ] Provider status is correct (`1` for active providers; `0`/`3` only when intentional).
- [ ] Gradle build was run.
- [ ] Gradle build was not run; reason explained below.
- [ ] `repo.json` impact was checked, if relevant.
- [ ] `plugins.json` impact was checked, if relevant.

Build result:

```text
Example:
./gradlew make
BUILD SUCCESSFUL
```

If build was not run, explain why:

```text

```

---

## Scope Safety

- [ ] This PR only touches files related to the stated provider/module/task.
- [ ] No unrelated providers were modified.
- [ ] No broad formatting-only changes were made.
- [ ] No `.github/ISSUE_TEMPLATE/*` files were modified unless this PR is specifically about issue templates.
- [ ] No `.github/pull_request_template.md` changes were made unless this PR is specifically about PR template maintenance.

---

## Reviewer Notes

Add any extra notes, mirrors, source changes, Cloudflare behavior, regional blocking, or known limitations.

```text

```

---

## Indonesian Notes / Catatan Indonesia

Ringkasan singkat untuk maintainer Indonesia, jika perlu:

```text

```
