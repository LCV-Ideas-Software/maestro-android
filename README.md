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

It departs from that source in two declared ways. The agent's report is read as
strict JSON — the canonical prompt already asks for JSON and treats a truncated
report as a contract violation — using Jackson, the repository's first runtime
dependency, recorded in [`THIRDPARTY.md`](THIRDPARTY.md). And when the revised
text has a block that is not an unchanged copy of a received one, the report
must also declare, in `revised_block_origins`, where each revised block comes
from: without it, an edited block that moved is indistinguishable from an
added one. The operator's decision, and the residual it does not close, are
recorded in [Discussion #41](https://github.com/LCV-Ideas-Software/maestro-android/discussions/41).

The same module holds the rest of the protocol that decides whether a turn
counts. It covers the serial turn's output contract, the bibliographic
integrity gate and the anti-impoverishment quality guard, all ported from the
desktop's Rust. It also covers the draft
and revision prompts and the per-call cost in `BigDecimal`, both ported from
the web, which has the API-only prompts and the three per-provider rates. The
revision prompt asks for exactly what the lock enforces. The cost cap follows
section 7.1 of the specification: amounts are summed and compared at eight
decimals, and a call is allowed when the running total plus its estimate does
not exceed the cap.

Since MAEANDR-18 the module also holds the final-release audit, the gate that
decides whether a text may be delivered. By the operator's decision of
24/09/2026 it ports the desktop's current five stages, not the three the web
ported: bibliographic integrity, the ABNT citation gate, a 30-link capacity,
the link-integrity engine, and the rule that no link is released without an
explicit review against its current URL and content hash. Without a structured
citation manifest (`citation_manifest.v1`, attached to the session), every
citation the ABNT gate detects blocks delivery, as on the desktop. In a few
places it is deliberately stricter than the desktop, fixing defects the desktop
shares; section 2.2 of the specification lists them. Raw HTML inside the
Markdown final text blocks the release (operator's decision of 25/09/2026);
`commonmark-java` tells it from prose, following the CommonMark specification,
not a scanner of our own (operator's decision of 24/09/2026; recorded in
[`THIRDPARTY.md`](THIRDPARTY.md)). The application is a product for the
public, not a copy of the operator's internal apps: it reaches users through
two channels, the Play Store (R$ 10.00 initially, a convenience fee that may
change) and, free of charge, the GitHub Releases of this repository
(operator's decision of 25/09/2026, Discussion #62). What needs
the network or the device reaches the module through interfaces: URL parsing,
name resolution, fetching and search belong to `:core:provedores`, and the link
review records to `:core:sessao`. Its regular expressions use explicit
character classes and flags passed as options, because Android runs
`java.util.regex` on ICU, where the JVM's `UNICODE_CHARACTER_CLASS` throws.

The second module is `:core:provedores`, also pure Kotlin and tested on the
JVM against a fake HTTP server. It holds the six AI providers, each built from
its official API documentation, reconfirmed on 23/09/2026. Every request uses
reasoning at the provider's maximum and a 64 000-token output ceiling. Four of
them get `store: false`; the other two have no such field. The network policy
comes from the canonical desktop: two attempts at most, and a wait on HTTP 429
that honours `Retry-After`. The module reads the API key through an interface
and never touches the Android Keystore; that implementation is
`:core:seguranca`.

The same module holds the network side of the link audit (MAEANDR-18, second
of two pull requests), implementing the interfaces of `:core:protocolo` with
official components, by the operator's decisions of 25/09/2026: URLs are
parsed by OkHttp's `HttpUrl`; every name is resolved through Google Public
DNS over HTTPS (`okhttp-dnsoverhttps`, `dns.google`), with no fallback to the
network's own DNS, and every answer is checked against the blocked ranges
before any connection; `robots.txt` is read by crawler-commons, the reference
parser for RFC 9309; only `https://` links are collected. The fetch itself
follows the desktop: a fresh guarded client without proxy, cookies or
automatic redirects, five hops at most, each validated again, an 8 MiB body
cap, and the same interaction classification. Evidence search uses the
Crossref and OpenAlex APIs without keys. The app may carry an optional
contact e-mail, the user's own, that goes only to Crossref, as its
documentation asks; nothing from LCV Ideas & Software identifies the user in
any request. Section 5.4 of the specification records the decisions and the
two places where the RFC 9309 parser reads a `robots.txt` differently from
the desktop.

The third module, `:core:seguranca`, is an Android library that keeps each
provider's API key on this device only. The key is encrypted with AES-256-GCM
by a key generated inside the Android Keystore, which cannot be exported:
StrongBox when the device has it, the trusted environment when it does not.
The ciphertext lives in the app's DataStore. Using that key requires the user
to have authenticated within a fixed time window, not once per call. Its tests
are instrumented, because the Keystore exists only on a device or an emulator.
Four of the five cases run on an emulator in CI on every pull request, as a
required check; the fifth needs StrongBox hardware, which no available device
has, and its test runs only where that hardware exists.

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
coverage. Kotlin is covered by CodeQL code scanning: since 24/09/2026 the
Default setup analyzes `java-kotlin`, built with autobuild, on CodeQL 2.27.1,
the first version that supports the Kotlin 2.4.20 this project is pinned to
(MAEANDR-16; [official
changelog](https://github.blog/changelog/2026-09-25-codeql-2-27-1-adds-c-and-c-query-and-kotlin-2-4-20-support/)).
Code Quality does not cover it: its rule-based analysis supports
C#, Go, Java, JavaScript, Python, Ruby and TypeScript, and its `none` build mode
cannot extract Kotlin. So the placeholder is still the only source Code Quality
analyzes here, and it stays until Code Quality covers Kotlin (MAEANDR-20).

## Automation baseline

- The `CI` workflow compiles, analyzes and tests the project on every pull
  request and every push to `main`: Gradle wrapper validation, `assembleDebug`,
  `lintDebug` and unit tests — including those of `:core:protocolo`, which run
  on the JVM — with the same JDK the publishing workflow uses. A separate job
  runs the instrumented tests of `:core:seguranca` on an emulator managed by
  the Android Gradle Plugin (Gradle Managed Devices). Both jobs are required
  checks in the repository ruleset.
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
