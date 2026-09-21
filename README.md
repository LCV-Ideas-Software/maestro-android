# Maestro Android

[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/14230/badge)](https://www.bestpractices.dev/projects/14230)

Public repository for the Android edition of Maestro Editorial AI. It carries
the reviewed governance, security, release and documentation baseline, and —
since 21/09/2026 — the first module of the native port. There is no shippable
application yet: no user interface, no provider client, and no release.

## Canonical tracking

| Surface | Canonical resource |
| --- | --- |
| GitHub repository | [LCV-Ideas-Software/maestro-android](https://github.com/LCV-Ideas-Software/maestro-android) |
| GitHub Project | [Project #20 — maestro-android](https://github.com/orgs/LCV-Ideas-Software/projects/20) |
| Linear | Team and Project `maestro-android` |
| Bootstrap work | [GitHub Issue #1](https://github.com/LCV-Ideas-Software/maestro-android/issues/1) and Linear `MAEANDR-3` |

A GitHub Issue is created or linked only when an explicit and unequivocal
Linear counterpart exists. Private planning, credentials, user material, and
unpublished operational details must remain in their private systems.

## Product boundaries

The intended Android product is a free, privacy-preserving editorial workbench.
Its current design constraints are:

- package name `dev.lcv.maestro`;
- bring-your-own-key credentials stored only through Android Keystore-backed
  device storage;
- provider keys never transit or persist in LCV Ideas & Software
  infrastructure;
- protocols, results, and account-bound data remain under explicit user
  control;
- no advertising, analytics, or tracking SDKs.

These are design commitments, not claims that an application has already been
implemented.

## Current state

The Gradle project has been on the fleet baseline since 21/09/2026
(MAEANDR-14): package name `dev.lcv.maestro`, `compileSdk` and `targetSdk` 37,
`minSdk` 34, a `gradle/libs.versions.toml` version catalog, and the Kotlin
Gradle Plugin declared. No signing material lives here; it is injected at build
time by the publishing workflow.

The first module of the port is `:core:protocolo` — pure Kotlin, no Android
dependency, tested on the JVM. It carries the approved-content lock: text is
segmented into blocks, and a revision may only change, reorder or add blocks it
declared in the `changed_blocks` section of its report. That unit is ported from
`maestro-app/src-tauri/src/editorial_content_lock.rs`, which the web module
itself names as canonical, together with the canonical Rust test suite.

The native port is specified in
[`docs/especificacao-v1.md`](docs/especificacao-v1.md) (in Portuguese), written
before any Kotlin, as `calculadora-android` did. It fixes the scope unit by
unit, the `:core:*` + `:app` module split, the six AI providers with the model
and API contract each one documents today, on-device key storage through the
Android Keystore, the accepted risks, and the pendencies that remain open. That
specification also recorded the Gradle baseline this repository had to reach in
its first code change, and MAEANDR-14 reached it.

The inert [`quality/code-quality-probe.js`](quality/code-quality-probe.js)
exists solely to give GitHub Code Quality a deterministic supported-language
target. It is not loaded by Pages or any runtime and does not represent Kotlin
coverage — and it still cannot be removed, because CodeQL does not support the
Kotlin 2.4.20 this project is pinned to, so `java-kotlin` stays out of the
analysis for now.

## Automation baseline

- The `CI` workflow compiles, analyzes and tests the project on every pull
  request and every push to `main`: Gradle wrapper validation, `assembleDebug`,
  `lintDebug` and unit tests — including those of `:core:protocolo`, which run
  on the JVM — with the same JDK the publishing workflow uses.
- GitHub CodeQL Default setup analyzes the supported content. The duplicate
  advanced-setup workflow is not maintained in this repository.
- Dependency Review evaluates pull requests to `main`.
- Zizmor analyzes workflow security and publishes SARIF.
- OpenSSF Scorecard analyzes the default branch as an observability signal, not
  a pull-request gate; its SARIF stays in GitHub code scanning, without a
  separate publication to the public Scorecard API.
- Dependabot checks GitHub Actions every day, including weekends, at 05:00
  in fixed UTC-03:00,
  with a seven-day cooldown except for official `actions/*` and `github/*`
  updates. Minor and patch updates are grouped; major updates remain separate.
  The Gradle ecosystem was declared on 17/09/2026 (PANDROI-38), alongside the
  real Gradle project, and its `ignore` list carries the build-classpath
  transitives the Dependabot security job cannot update. The
  `kotlin-gradle-plugin` line left that list on 21/09/2026, when the plugin
  became a declared direct dependency in the version catalog.
  Security updates have their own group and do not wait for the version-update
  schedule or cooldown. If one member fails, diagnose it and adjust native
  grouping so other fixes can proceed through the required checks.
- A repository-local workflow enables GitHub's native squash auto-merge for
  same-repository Dependabot pull requests against `main`, subject to the
  effective native rules and checks. Grouping does not restrict auto-merge to
  minor and patch updates. There is no merge queue or central controller.
- The official Linear Release Action and CLI v0.17.2 record pushed `main`
  history in the corresponding continuous Linear pipeline. This does not
  publish an Android application, npm package, or Windows release.
- GitHub Pages deploys only the sanitized `site/` directory to
  <https://maestro-android.lcv.dev>; search indexing remains disabled while the
  product has no public implementation.
- `publish-play.yml`, dispatched manually, builds the release App Bundle, sends
  it to the chosen Google Play track and refuses to publish when the digest Play
  received is not the artifact the job built. The release notes travel with it,
  from `play/release-notes/pt-BR.txt`, because the API takes them in the track
  update and a publication without them reaches the store with an empty "what's
  new". A `release_status` input carries `completed` or `draft`: an app that has
  never been published only accepts `draft` on the public track, and that first
  publication is finished in the Play Console. A production publication also
  records a GitHub Release with tag `vXX.XX.XX`, carrying the universal APK that
  Google Play generated and signed with the app signing key — the same binary the
  store distributes — plus `SHA256SUMS` and a provenance attestation.
- `record-play-release.yml`, also dispatched manually, records that GitHub
  Release for a version **already** on the store, given its `versionCode`,
  without rebuilding or re-uploading anything. It is the path after a first
  publication is completed in the Console, when the publishing workflow has
  already finished and re-dispatching it would only re-upload a `versionCode`
  Play refuses. Measured on 20/09/2026 in calculadora-android: Play makes the
  universal APK available as soon as it processes the bundle, before any rollout.

  This repository has no application yet, so `play/release-notes/pt-BR.txt` does
  not exist and the publishing workflow stops before building, saying so. That is
  the intended gate, not a defect: nothing here is ready to reach a store.

Every external GitHub Action is pinned to a full commit SHA directly in its
workflow. The third-party inventory is in [THIRDPARTY.md](THIRDPARTY.md). Native
GitHub/Linear and GitHub/Slack integrations remain in place. No repository is
responsible for controlling this repository's lifecycle.

## Contributing and security

Read [CONTRIBUTING.md](CONTRIBUTING.md) and [INBOUND.md](INBOUND.md) before
proposing a change. Report
vulnerabilities and sensitive operational concerns through the private route
in [SECURITY.md](SECURITY.md), never through a public Issue or Discussion.

## License

Copyright © 2026 LCV Ideas & Software.

Original content in this repository is licensed under the GNU Affero General
Public License, version 3 or any later version. See [LICENSE](LICENSE) and
[NOTICE](NOTICE). Third-party components retain their own licenses as listed in
[THIRDPARTY.md](THIRDPARTY.md).
