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
When a real Android scaffold is introduced, add the official Gradle dependency
updates, wrapper validation, lint, test, and assemble checks, and verify native
CodeQL Default setup covers its Java/Kotlin source in that same reviewed change.

The inert [`quality/code-quality-probe.js`](quality/code-quality-probe.js)
exists solely to give GitHub Code Quality a deterministic supported-language
target before real application source exists. It is not loaded by Pages or any
runtime and does not represent Kotlin coverage.

## Automation baseline

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
  Gradle coverage is intentionally absent until a real Gradle project exists.
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
