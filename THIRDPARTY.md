# Third-party inventory

This repository has no Android or production-runtime dependency. The following
table inventories every GitHub Action referenced directly by a workflow at
bootstrap. Every direct `uses:` reference is fixed to the listed full commit
SHA. The generated `.github/workflows/actions.lock` is the authoritative,
machine-verifiable inventory of both direct and transitive Action revisions.

| Component | Version | Commit SHA | License | Purpose |
| --- | --- | --- | --- | --- |
| `actions/checkout` | v7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` | [MIT](https://github.com/actions/checkout/blob/3d3c42e5aac5ba805825da76410c181273ba90b1/LICENSE) | Read repository content and complete Git history |
| `github/codeql-action` | v4.37.9 | `cdf488f595d80d6e07e03d4674febd5ab45fa938` | [MIT](https://github.com/github/codeql-action/blob/cdf488f595d80d6e07e03d4674febd5ab45fa938/LICENSE) | Initialize/analyze CodeQL and upload Scorecard SARIF |
| `actions/dependency-review-action` | v5.0.0 | `a1d282b36b6f3519aa1f3fc636f609c47dddb294` | [MIT](https://github.com/actions/dependency-review-action/blob/a1d282b36b6f3519aa1f3fc636f609c47dddb294/LICENSE) | Review dependency changes in pull requests and merge groups |
| `zizmorcore/zizmor-action` | v0.6.2 | `3dc1ecc9bcb9e94e9b2c709687979e1298497054` | [MIT](https://github.com/zizmorcore/zizmor-action/blob/3dc1ecc9bcb9e94e9b2c709687979e1298497054/LICENSE) | Audit GitHub Actions and upload SARIF |
| `ossf/scorecard-action` | v2.4.4 | `2d1146689b8cda280b9bc96326124645441f03bc` | [Apache-2.0](https://github.com/ossf/scorecard-action/blob/2d1146689b8cda280b9bc96326124645441f03bc/LICENSE) | Assess supply-chain posture |
| `actions/upload-artifact` | v7.0.1 | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` | [MIT](https://github.com/actions/upload-artifact/blob/043fb46d1a93c77aae656e7c1c64a875d1fc6a0a/LICENSE) | Retain the Scorecard SARIF artifact |
| `actions/configure-pages` | v6.0.0 | `45bfe0192ca1faeb007ade9deae92b16b8254a0d` | [MIT](https://github.com/actions/configure-pages/blob/45bfe0192ca1faeb007ade9deae92b16b8254a0d/LICENSE) | Configure the Pages build |
| `actions/upload-pages-artifact` | v5.0.0 | `fc324d3547104276b827a68afc52ff2a11cc49c9` | [MIT](https://github.com/actions/upload-pages-artifact/blob/fc324d3547104276b827a68afc52ff2a11cc49c9/LICENSE) | Upload the sanitized `site/` artifact |
| `actions/deploy-pages` | v5.0.0 | `cd2ce8fcbc39b97be8ca5fce6e763baed58fa128` | [MIT](https://github.com/actions/deploy-pages/blob/cd2ce8fcbc39b97be8ca5fce6e763baed58fa128/LICENSE) | Deploy the trusted Pages artifact |
| `linear/linear-release-action` | v0.17.1 | `3f31fcf14c110cc53579fcc3575a26d469c413b4` | [MIT](https://github.com/linear/linear-release-action/blob/3f31fcf14c110cc53579fcc3575a26d469c413b4/LICENSE) | Create a release in the corresponding Linear pipeline |

`github/codeql-action` is MIT-licensed. The CodeQL CLI bundle selected by the
pinned Action is separately governed by the immutable
[GitHub CodeQL Terms and Conditions](https://github.com/github/codeql-cli-binaries/blob/0d65148c254764ec294892a35e644accd5677ed5/LICENSE.md)
and the Enterprise GitHub Code Security entitlement.

## Accepted upstream constraints

### OpenSSF Scorecard runtime image

The Scorecard Action is pinned to an immutable source commit, but the current
official release delegates execution to a published runtime image whose
provenance remains controlled upstream. Because this repository is public,
`publish_results: true` contributes the result to the public Scorecard API.
The job grants `id-token: write` only to authenticate that publication with
GitHub OIDC; the SARIF result is also uploaded to GitHub code scanning.

### Linear Release installer

The official Linear Action is pinned to an immutable source commit and the CLI
version is explicit. Its installer currently downloads the selected CLI
without a checksum, signature, or artifact-attestation gate. The upstream gap
is tracked in
[linear/linear-release-action#59](https://github.com/linear/linear-release-action/issues/59).
The pipeline access key is confined to the dedicated `linear-release`
environment, and any sync failure leaves the workflow red.

## Repository license

Original repository content is licensed under AGPL-3.0-or-later; see
[LICENSE](LICENSE) and [NOTICE](NOTICE). The licenses above apply only to their
respective third-party components.
