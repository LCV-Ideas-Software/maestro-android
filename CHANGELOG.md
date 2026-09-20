# Changelog

All material changes to Maestro Android are recorded here.

## [Unreleased]

### Added

- Bring the Google Play publication pipeline to the fleet baseline, PANDROI-40,
  by copying it verbatim from calculadora-android, where it was exercised end to
  end on a real publication on 20/09/2026. Both workflow files are byte-identical
  to that repository's, because an implementation already reviewed, merged and
  proven in production is worth more than a re-derivation of it.

  `publish-play.yml` gains four things it lacked. Release notes now travel with
  the track update, as `releases[].releaseNotes[]` of `LocalizedText {language,
  text}` with a BCP-47 tag, read from `play/release-notes/pt-BR.txt` and checked
  against the 500-character-per-language ceiling before the build — without them a
  publication reaches the store with an empty "what's new". A `release_status`
  input carries `completed` or `draft`, because an app that has never been
  published only accepts `draft` on the public track: *"Only releases with status
  draft may be created on draft app"*, and a person finishes that first
  publication in the Console. Every call to the Play API goes through a helper
  that prints which call failed and what Google Play answered, since
  `--fail-with-body` writes to standard output and the calls redirect it —
  without the helper an HTTP 400 reaches the log as `curl: (22)` and nothing
  more, and turning on debug logging does not recover it. And a production
  publication records a GitHub Release with the universal APK Play signed,
  `SHA256SUMS` and a provenance attestation.

  `record-play-release.yml` is new: it records that Release for a version already
  on the store, given its `versionCode`, without rebuilding or re-uploading.
  It exists because a first publication cannot be automated end to end — by the
  time the Console finishes it, the publishing workflow has exited, and
  re-dispatching it would re-upload a `versionCode` Play refuses.

  This repository has no application yet, so no release-notes file is created and
  the publishing workflow stops before building until one is written. The
  `versionCode` 1 was already consumed by the internal publication of 17/09/2026,
  so the first real version must carry 2 or higher.

### Changed

- Complete the third-party inventory for the Actions the workflows actually use.
  `actions/attest` and `actions/download-artifact` arrive with this change;
  `actions/setup-java`, `gradle/actions` and `google-github-actions/auth` were
  already used by `publish-play.yml` and were missing from the table.
  `gradle/actions` is recorded as its own `LICENSE` states — primarily MIT, with
  a vendored proprietary component — rather than flattened to MIT, which is why
  GitHub classifies that repository as `NOASSERTION`.

### Changed

- Update the official CodeQL Action to v4.38.0 and Zizmor Action to v0.6.4,
  retaining full commit pins and aligning the current third-party inventory.

- Aligned this pre-application repository with the fleet's native governance:
  GitHub CodeQL Default setup, pull-request Dependency Review and Pages checks,
  direct Scorecard SARIF, and Zizmor without retired merge-queue events.
- Schedule GitHub Actions Dependabot updates every day, including weekends,
  at 05:00 in fixed UTC-03:00, with grouped minor/patch updates, separate major updates,
  and the existing selective seven-day cooldown. Added repository-local native
  auto-merge subject to the effective GitHub rules and checks.
- Group security updates separately from version updates.
- Aligned the official Linear CLI to v0.17.2 while preserving the continuous
  `main` commit-history pipeline rather than introducing application publishing.
- Replaced obsolete central-controller and merge-queue contribution instructions
  with native repository-local governance, explicit operator approval boundaries,
  and repository-local inbound rights.
- Preserved the complete license, notice, static Pages site, and inert quality
  probe; no Android scaffold, runtime dependency, or release version was added.

### Added

- Established the public repository baseline for Maestro Android.
- Documented the canonical GitHub and Linear topology and product privacy
  boundaries.
- Added the organization-standard security, supply-chain, Pages, Dependabot,
  and official Linear Release workflows.
- Added a deliberately inert JavaScript probe for GitHub Code Quality while no
  Android application source exists.
- Added repository ownership and organization sponsorship metadata.
- Declared AGPL-3.0-or-later licensing and a complete bootstrap third-party
  automation inventory.

### Fixed

- Removed the obsolete Actions dependency lock and its workflow onboarding
  markers to restore workflow startup after Dependabot updates. Direct SHA
  pins, workflow behavior and repository security settings are unchanged.
