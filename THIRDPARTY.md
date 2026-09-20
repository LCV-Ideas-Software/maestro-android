# Third-party inventory

This repository has no Android or production-runtime dependency. The following
table records the direct automation dependencies. The current immutable pins
are the full commit SHAs in each workflow's `uses:` references. Transitive
Action dependencies remain defined by those pinned upstream actions.

| Component | Version | Commit SHA | License | Purpose |
| --- | --- | --- | --- | --- |
| `actions/checkout` | v7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` | [MIT](https://github.com/actions/checkout/blob/3d3c42e5aac5ba805825da76410c181273ba90b1/LICENSE) | Read repository content and complete Git history |
| `github/codeql-action` | v4.38.0 | `b96794f015dfd88f77b49b1c93e0fa7110f94c63` | [MIT](https://github.com/github/codeql-action/blob/b96794f015dfd88f77b49b1c93e0fa7110f94c63/LICENSE) | Upload Scorecard SARIF; source analysis uses GitHub-managed CodeQL Default setup |
| `actions/dependency-review-action` | v5.0.0 | `a1d282b36b6f3519aa1f3fc636f609c47dddb294` | [MIT](https://github.com/actions/dependency-review-action/blob/a1d282b36b6f3519aa1f3fc636f609c47dddb294/LICENSE) | Review dependency changes in pull requests |
| `zizmorcore/zizmor-action` | v0.6.4 | `cc914d7f3750a2d13d75c7f184a1060aa0e9d482` | [MIT](https://github.com/zizmorcore/zizmor-action/blob/cc914d7f3750a2d13d75c7f184a1060aa0e9d482/LICENSE) | Audit GitHub Actions and upload SARIF |
| `ossf/scorecard-action` | v2.4.4 | `2d1146689b8cda280b9bc96326124645441f03bc` | [Apache-2.0](https://github.com/ossf/scorecard-action/blob/2d1146689b8cda280b9bc96326124645441f03bc/LICENSE) | Assess supply-chain posture |
| `actions/upload-artifact` | v7.0.1 | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` | [MIT](https://github.com/actions/upload-artifact/blob/043fb46d1a93c77aae656e7c1c64a875d1fc6a0a/LICENSE) | Retain the Scorecard SARIF; carry the Play-signed APK to the release job |
| `actions/download-artifact` | v8.0.1 | `3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c` | [MIT](https://github.com/actions/download-artifact/blob/3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c/LICENSE) | Fetch the Play-signed APK in the release job |
| `actions/attest` | v4.2.2 | `1e69f48acb82d1966a394da916b4c1698aa569d6` | [MIT](https://github.com/actions/attest/blob/1e69f48acb82d1966a394da916b4c1698aa569d6/LICENSE) | Build provenance attestation for the released APK |
| `actions/setup-java` | v6.0.1 | `de7274f081f381c8f8158605e0321c36c376e2e6` | [MIT](https://github.com/actions/setup-java/blob/de7274f081f381c8f8158605e0321c36c376e2e6/LICENSE) | Provide the JDK the Android build requires |
| `gradle/actions` (`setup-gradle`, `wrapper-validation`) | v6.3.0 | `9c971963bec38e04b3d30dcc455b5382be2fdbfb` | [MIT, with a proprietary vendored component](https://github.com/gradle/actions/blob/9c971963bec38e04b3d30dcc455b5382be2fdbfb/LICENSE) | Validate the Gradle wrapper and run Gradle |
| `google-github-actions/auth` | v3.0.0 | `7c6bc770dae815cd3e89ee6cdf493a5fab2cc093` | [Apache-2.0](https://github.com/google-github-actions/auth/blob/7c6bc770dae815cd3e89ee6cdf493a5fab2cc093/LICENSE) | Exchange the OIDC token for Google credentials (Workload Identity Federation) |
| `actions/configure-pages` | v6.0.0 | `45bfe0192ca1faeb007ade9deae92b16b8254a0d` | [MIT](https://github.com/actions/configure-pages/blob/45bfe0192ca1faeb007ade9deae92b16b8254a0d/LICENSE) | Configure the Pages build |
| `actions/upload-pages-artifact` | v5.0.0 | `fc324d3547104276b827a68afc52ff2a11cc49c9` | [MIT](https://github.com/actions/upload-pages-artifact/blob/fc324d3547104276b827a68afc52ff2a11cc49c9/LICENSE) | Upload the sanitized `site/` artifact |
| `actions/deploy-pages` | v5.0.1 | `368f82528645a54fb793d4d04e342629a3f51346` | [MIT](https://github.com/actions/deploy-pages/blob/368f82528645a54fb793d4d04e342629a3f51346/LICENSE) | Deploy the trusted Pages artifact |
| `linear/linear-release-action` | v0.17.2 | `53ad0f863963e7f8e270fba18426bbb55ef55384` | [MIT](https://github.com/linear/linear-release-action/blob/53ad0f863963e7f8e270fba18426bbb55ef55384/LICENSE) | Create a release in the corresponding Linear pipeline |

`gradle/actions` declares itself "primarily licensed under the MIT License", and its own `LICENSE` states that the repository also carries a vendored component, `gradle-actions-caching`, that is **proprietary** and covered by a separate licence, detailed in its `DISTRIBUTION.md` and `NOTICE`. GitHub classifies the repository as `NOASSERTION` for that reason. Recorded as it is, rather than flattened to MIT.

`github/codeql-action` is MIT-licensed. GitHub manages source analysis through
CodeQL Default setup; the pinned workflow consumer only uploads Scorecard SARIF.
The CodeQL CLI is separately governed by the immutable
[GitHub CodeQL Terms and Conditions](https://github.com/github/codeql-cli-binaries/blob/0d65148c254764ec294892a35e644accd5677ed5/LICENSE.md)
and the Enterprise GitHub Code Security entitlement.

The official Linear action explicitly selects CLI v0.17.2 for continuous
commit-history releases. This repository publishes no application package and
has no runtime dependency notice bundle to generate.

## Accepted upstream constraints

### OpenSSF Scorecard runtime image

The Scorecard Action is pinned to an immutable source commit, but the current
official release delegates execution to a published runtime image whose
provenance remains controlled upstream. This repository uses
`publish_results: false`: results are retained as a workflow artifact and
uploaded directly to GitHub code scanning. The job does not request an OIDC
token or publish results separately to the public Scorecard API.

## Repository license

Original repository content is licensed under AGPL-3.0-or-later; see
[LICENSE](LICENSE) and [NOTICE](NOTICE). The licenses above apply only to their
respective third-party components.
