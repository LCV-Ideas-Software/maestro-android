# Third-party inventory

This repository's production-runtime dependencies are recorded under [Runtime
dependencies](#runtime-dependencies); those of `:core:seguranca` are
Android-specific. The table
below records the direct automation dependencies. The current immutable pins
are the full commit SHAs in each workflow's `uses:` references. Transitive
Action dependencies remain defined by those pinned upstream actions.

| Component | Version | Commit SHA | License | Purpose |
| --- | --- | --- | --- | --- |
| `actions/checkout` | v7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` | [MIT](https://github.com/actions/checkout/blob/3d3c42e5aac5ba805825da76410c181273ba90b1/LICENSE) | Read repository content and complete Git history |
| `github/codeql-action` | v4.38.0 | `b96794f015dfd88f77b49b1c93e0fa7110f94c63` | [MIT](https://github.com/github/codeql-action/blob/b96794f015dfd88f77b49b1c93e0fa7110f94c63/LICENSE) | Upload Scorecard SARIF; source analysis uses GitHub-managed CodeQL Default setup |
| `actions/dependency-review-action` | v5.0.0 | `a1d282b36b6f3519aa1f3fc636f609c47dddb294` | [MIT](https://github.com/actions/dependency-review-action/blob/a1d282b36b6f3519aa1f3fc636f609c47dddb294/LICENSE) | Review dependency changes in pull requests |
| `zizmorcore/zizmor-action` | v0.6.4 | `cc914d7f3750a2d13d75c7f184a1060aa0e9d482` | [MIT](https://github.com/zizmorcore/zizmor-action/blob/cc914d7f3750a2d13d75c7f184a1060aa0e9d482/LICENSE) | Audit GitHub Actions and upload SARIF |
| `ossf/scorecard-action` | v2.4.4 | `2d1146689b8cda280b9bc96326124645441f03bc` | [Apache-2.0](https://github.com/ossf/scorecard-action/blob/2d1146689b8cda280b9bc96326124645441f03bc/LICENSE) | Assess supply-chain posture |
| `actions/upload-artifact` | v7.0.1 | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` | [MIT](https://github.com/actions/upload-artifact/blob/043fb46d1a93c77aae656e7c1c64a875d1fc6a0a/LICENSE) | Retain the Scorecard SARIF and the instrumented test results; carry the Play-signed APK to the release job |
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
commit-history releases.

## Runtime dependencies

| Component | Version | License | Purpose |
| --- | --- | --- | --- |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.2 | [Apache-2.0](https://github.com/FasterXML/jackson-databind/blob/jackson-databind-2.22.2/LICENSE) | Read the `maestro_revision_report` in `:core:protocolo` |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.2 | [Apache-2.0](https://github.com/FasterXML/jackson-core/blob/jackson-core-2.22.2/LICENSE) | Transitive: the streaming parser, and `StreamReadFeature.STRICT_DUPLICATE_DETECTION` |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | [Apache-2.0](https://github.com/FasterXML/jackson-annotations/blob/jackson-annotations-2.22/LICENSE) | Transitive of `jackson-databind` |
| FastDoubleParser, shaded inside `jackson-core` | bundled | MIT, © 2023 Werner Randelshofer | Number parsing inside `jackson-core`; not a separate artifact |
| Schubfach, copied inside `jackson-core` | bundled | MIT, © 2018-2020 Raffaello Giulietti | Number writing inside `jackson-core`; not a separate artifact |
| `org.commonmark:commonmark` | 0.30.0 | [BSD-2-Clause](https://github.com/commonmark/commonmark-java/blob/commonmark-parent-0.30.0/LICENSE.txt), © 2015 Atlassian Pty Ltd | Recognise the raw HTML in the Markdown final text (CommonMark 0.31.2, section 6.6) for the citation audit in `:core:protocolo` |

Version alignment across the three is held by the `jackson-bom` platform that
`jackson-databind` brings in, so the catalogue pins one version and the BOM
constrains the rest. Measured contribution to the runtime classpath: 1 668 KB,
580 KB and 82 KB respectively, 2 330 KB in total before shrinking.

The `maestro_revision_report` is JSON by contract — the canonical prompt asks
for "JSON-like audit data" and already declares a truncated report a contract
violation — and `:core:protocolo` is an integrity gate. Reading that JSON with
a hand-written scanner produced findings in four consecutive review rounds,
because a hand-written scanner disagrees with the specification somewhere, and
in a gate every disagreement is an authorization that should not have been
granted. `STRICT_DUPLICATE_DETECTION`, which FasterXML documents as disabled by
default, is what closes duplicate `block_id`, `protocol_basis` and
`change_type` fields without any logic of our own.

The last two rows are not declared anywhere in the dependency graph: they were
found by reading `META-INF/NOTICE` inside `jackson-core-2.22.2.jar`, which
records both as bundled MIT code and names their licence files. Each of the
three jars also carries its own `META-INF/LICENSE` (Apache-2.0) and
`META-INF/NOTICE`.

`commonmark` has no runtime dependencies of its own; its POM declares only
test-scoped ones. It contributes 215 KB to the runtime classpath. The final
text is Markdown, and the citation audit has to tell raw HTML from prose to
refuse it (the final text carries no HTML, by the operator's decision of
25/09/2026); a hand-written recogniser took six review rounds and
kept disagreeing with the specification, so the operator decided on 24/09/2026
to use the Java implementation of CommonMark instead.

No binary this repository distributes contains them yet: `:app` does not depend
on `:core:protocolo`. The first APK that includes the module has to carry what
the licences require of a distributed work — a copy of the Apache-2.0 text and
the attribution in the three Jackson `NOTICE` files, the MIT notices of
FastDoubleParser and Schubfach, and the BSD-2-Clause copyright notice,
conditions and disclaimer of `commonmark` — and the repository `NOTICE` does
not carry them today.

### `:core:provedores`

| Component | Version | License | Purpose |
| --- | --- | --- | --- |
| `com.squareup.okhttp3:okhttp` (`okhttp-jvm`) | 5.5.0 | [Apache-2.0](https://github.com/square/okhttp/blob/parent-5.5.0/LICENSE.txt) | HTTP transport to the six AI providers |
| Public Suffix List, bundled inside `okhttp-jvm` | bundled | [MPL-2.0](https://publicsuffix.org/list/), © Mozilla Foundation and contributors | `okhttp3/internal/publicsuffix/PublicSuffixDatabase.list`, used by OkHttp for cookie domains; not a separate artifact |
| `com.squareup.okhttp3:okhttp-coroutines` | 5.5.0 | [Apache-2.0](https://github.com/square/okhttp/blob/parent-5.5.0/LICENSE.txt) | OkHttp's official coroutine bridge: cancelling the coroutine cancels the call |
| `com.squareup.okio:okio` (`okio-jvm`) | 3.18.1 | [Apache-2.0](https://github.com/square/okio/blob/parent-3.18.1/LICENSE.txt) | Transitive of OkHttp |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` (`-jvm`) | 1.11.0 | [Apache-2.0](https://github.com/Kotlin/kotlinx.coroutines/blob/1.11.0/LICENSE.txt) | Suspending provider calls and cancellable waits |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.20 | [Apache-2.0](https://github.com/JetBrains/kotlin/blob/v2.4.20/license/LICENSE.txt) | The Kotlin standard library, needed by every Kotlin module, `:core:protocolo` included; it was missing from this inventory |

The module also uses `jackson-databind`, recorded above, to build the request
bodies and read the responses. Measured contribution to the runtime classpath:
939 KB (`okhttp-jvm`), 7 KB (`okhttp-coroutines`), 383 KB (`okio-jvm`),
1 540 KB (`kotlinx-coroutines-core-jvm`) and 1 810 KB (`kotlin-stdlib`),
before shrinking.

Retrofit is not used. Each provider is a single `POST` endpoint whose JSON body
is built with Jackson, so Retrofit would only wrap the same OkHttp client; the
operator decided on 23/09/2026 to use OkHttp alone. The Public Suffix List row
was found by listing the contents of `okhttp-jvm-5.5.0.jar`, not in the
dependency graph. MPL-2.0 is a file-level copyleft: distributing it unmodified,
as OkHttp ships it, requires telling recipients where its source form is
available, which the row's link does. The first APK that includes the module
has to carry that notice along with the Apache-2.0 text.

Test-only dependencies (`mockwebserver3`, `kotlinx-coroutines-test`, JUnit and
`kotlin-test`) never reach a distributed binary and are not listed.

### `:core:seguranca`

| Component | Version | License | Purpose |
| --- | --- | --- | --- |
| `androidx.datastore:datastore-preferences` and the DataStore artifacts it brings (`datastore`, `datastore-core`, `datastore-core-okio`, `datastore-preferences-core`, `datastore-preferences-proto`) | 1.2.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | Keep each provider's encrypted API key in the app's DataStore |
| `androidx.datastore:datastore-preferences-external-protobuf` | 1.2.1 | BSD-3-Clause, as its `META-INF/androidx/datastore/datastore-preferences-external-protobuf/LICENSE.txt` states | Protocol Buffers, repackaged by DataStore under `androidx.datastore.preferences.protobuf`, for the file format |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` and `-json` (`-jvm`) | 1.7.3 | [Apache-2.0](https://github.com/Kotlin/kotlinx.serialization/blob/v1.7.3/LICENSE.txt) | Transitive of `datastore-core-okio` |
| `com.squareup.okhttp3:okhttp-android` | 5.5.0 | [Apache-2.0](https://github.com/square/okhttp/blob/parent-5.5.0/LICENSE.txt) | The Android variant of OkHttp that `:core:provedores` resolves to on Android, in place of `okhttp-jvm`; it carries the same Public Suffix List, as `assets/PublicSuffixDatabase.list` |
| `androidx.annotation:annotation` (`-jvm`) | 1.10.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | Transitive of `okhttp-android` and DataStore |
| `androidx.startup:startup-runtime` | 1.2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | Transitive of `okhttp-android` |
| `androidx.tracing:tracing` | 1.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | Transitive of `androidx.startup` |
| `org.jetbrains:annotations` | 23.0.0 | [Apache-2.0](https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt) | Transitive of `kotlin-stdlib` and `kotlinx-coroutines-core`; it was missing from this inventory for `:core:provedores` too |

The module also uses `:core:provedores`, recorded above. The rows come from the
resolved `releaseRuntimeClasspath` of the module, measured on 23/09/2026, and
the licences from each artifact's POM. The BSD-3-Clause row's POM declares
that licence, and the licence file inside the jar confirms it; what the POM
does not show is that the code is Protocol Buffers, which the jar's package,
`androidx.datastore.preferences.protobuf`, does. Measured size of each artifact, before shrinking: 27 KB,
205 KB, 31 KB, 36 KB and 29 KB for the DataStore artifacts plus 18 KB for
`datastore-preferences`; 1 026 KB for the repackaged Protocol Buffers; 381 KB
and 264 KB for `kotlinx-serialization`; 924 KB for `okhttp-android`; 60 KB,
23 KB, 4 KB and 28 KB for the last four rows.

The first APK that includes the module has to carry, besides what the sections
above already list, the BSD-3-Clause notice of the repackaged Protocol
Buffers: its licence requires reproducing the copyright notice and conditions
in the documentation or other materials distributed with the binary.

Test-only dependencies (`androidx.test:runner`, `androidx.test.ext:junit`)
never reach a distributed binary and are not listed.

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
