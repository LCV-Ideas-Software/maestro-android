# Maestro Android

[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/14230/badge)](https://www.bestpractices.dev/projects/14230)

Public repository for the future Android edition of Maestro Editorial AI. This
repository currently contains the reviewed governance, security, release, and
documentation baseline; it does not yet contain an Android application.

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

There is no Gradle project, Android source set, package, signing configuration,
or production dependency. Do not add fake Gradle files merely to satisfy CI.
When a real Android scaffold is introduced, add the Gradle dependency update,
wrapper validation, lint, test, assemble, and Java/Kotlin CodeQL paths in that
same reviewed change.

The inert [`quality/code-quality-probe.js`](quality/code-quality-probe.js)
exists solely to give GitHub Code Quality a deterministic supported-language
target before real application source exists. It is not loaded by Pages or any
runtime and does not represent Kotlin coverage.

## Automation baseline

- CodeQL analyzes GitHub Actions and the inert JavaScript probe on pull
  requests, merge groups, `main`, and a schedule.
- Dependency Review evaluates pull requests and synthetic merge groups.
- Zizmor analyzes workflow security and publishes SARIF.
- OpenSSF Scorecard analyzes the default branch as an observability signal, not
  a pull-request gate.
- Dependabot checks GitHub Actions daily. Gradle coverage is intentionally
  absent until a real Gradle project exists.
- the official Linear Release Action records successful `main` history in the
  corresponding continuous Linear pipeline;
- GitHub Pages deploys only the sanitized `site/` directory to
  <https://maestro-android.lcv.dev>; search indexing remains disabled while the
  product has no public implementation.

Every external GitHub Action is pinned to a full commit SHA and recorded in
[THIRDPARTY.md](THIRDPARTY.md). The generated
`.github/workflows/actions.lock` is the machine-verifiable dependency
inventory.

## Contributing and security

Read [CONTRIBUTING.md](CONTRIBUTING.md) before proposing a change. Report
vulnerabilities and sensitive operational concerns through the private route
in [SECURITY.md](SECURITY.md), never through a public Issue or Discussion.

## License

Copyright © 2026 LCV Ideas & Software.

Original content in this repository is licensed under the GNU Affero General
Public License, version 3 or any later version. See [LICENSE](LICENSE) and
[NOTICE](NOTICE). Third-party components retain their own licenses as listed in
[THIRDPARTY.md](THIRDPARTY.md).
