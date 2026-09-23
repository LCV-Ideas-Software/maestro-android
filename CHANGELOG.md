# Changelog

All material changes to Maestro Android are recorded here.

## [Unreleased]

### Added

- Add `:core:seguranca`, the Android library that keeps each provider's API key
  on this device only (MAEANDR-19, specification section 6). The key is
  encrypted with AES-256-GCM by a key generated inside the Android Keystore,
  which cannot be exported, and the ciphertext lives in the app's DataStore.
  The provider's name is authenticated data, so one provider's ciphertext does
  not open as another's. `CofreDeChaves` implements the `FonteDeChave` of
  `:core:provedores`, which stays pure Kotlin.

  The operator's decisions of 21/09/2026 are written into the key when it is
  generated, because they cannot change afterwards: StrongBox when the device
  has it, falling back to the trusted environment when it does not, with the
  level in use read from the Keystore itself (`KeyInfo`); user authentication
  by time, not per operation, with a fixed window; and enrolling a new
  biometric does not invalidate the key. The window is a parameter: the value
  the product adopts has to come from real sessions (section 11, item 3). A
  failed read maps to the three causes of section 4.2 — an expired window asks
  for authentication, a permanently invalidated key is a lost secret, and
  anything else is "no key configured".

  The tests are instrumented, because the Keystore exists only on a device or
  an emulator. The operator decided on 23/09/2026 that four of the five cases
  in section 8 run in CI on every pull request, on an emulator managed by the
  Android Gradle Plugin (Gradle Managed Devices), in a job of their own; the
  first case needs StrongBox hardware, which the emulator lacks, and runs on a
  device that has it. That decision was checked on an Android 17 emulator
  first: the emulator has no StrongBox, so the fallback case runs against a
  real absence of the hardware; the test sets a screen PIN and
  re-authenticates without a screen; and the window expires as documented.
  The same measurement found that removing the screen lock deletes the
  Keystore key rather than invalidating it, which section 4.2 now records.

  That finding also sets a rule for the tests: clearing the lock of a real
  device would delete the authentication-bound keys of every app on it. The
  tests that set and clear a screen PIN therefore run only on a device with no
  screen lock at all, such as the CI emulator, and skip on any device that has
  one. The StrongBox case never touches the lock: it needs a device that
  already has one and was unlocked in the previous five minutes.

  `THIRDPARTY.md` records the module's runtime dependencies, measured from the
  resolved classpath: DataStore, which repackages Protocol Buffers under
  BSD-3-Clause, and `kotlinx-serialization`; and, because the classpath is now
  an Android one, the Android variant of OkHttp that `:core:provedores`
  resolves to, with the `androidx` libraries it brings. `org.jetbrains:
  annotations`, missing for `:core:provedores` too, is added.

- Add `:core:provedores`, the second delivery of the native port (MAEANDR-19):
  the six AI providers as pure Kotlin on the JVM, with no Android dependency.
  The API key is requested through `FonteDeChave`, which `:core:seguranca`
  will implement over the Keystore. The source answers with one of four
  readings — the key, no key, authentication required, or a secret that can no
  longer be decrypted — and each unavailable reading is its own outcome, with
  no request sent: section 4.2 of the specification separates the three causes
  of a failed read, and merging them would ask the user to retype a key that is
  intact. The key's text representation never shows its value.

  **The request body of each provider comes from its official documentation,
  reconfirmed on 23/09/2026.** Neither canonical source uses the new
  transports: the desktop still calls Perplexity's `/v1/sonar`, which stops on
  27/09/2026, and Gemini's `generateContent`, and the web reaches Gemini through
  Vertex. The reconfirmation corrected the specification in four places:
  - Gemini's Interactions endpoint is `/v1beta/interactions`, not `/v1beta2/`;
  - the Perplexity model is `perplexity/sonar` with the `xhigh` preset, the
    Agent API's only Perplexity-owned model and the operator's decision of
    22/09/2026 for the web;
  - DeepSeek now exposes reasoning effort, inside `thinking`;
  - xAI exposes `store`, so four providers receive `store: false` (OpenAI,
    Gemini, xAI, Perplexity) and two never see the field (Anthropic, DeepSeek).
    The specification now also says what `store: false` does not do: xAI keeps
    every request for 30 days for abuse auditing regardless, unless the user's
    account enables zero data retention.

  The operator decided on 23/09/2026:
  - reasoning at each provider's maximum;
  - a 64 000-token output ceiling per call, the starting point Anthropic
    documents for `max` effort;
  - OkHttp alone, without Retrofit.

  Only the final answer is read from each response — never a reasoning block,
  which would otherwise reach `<maestro_final_text>`. Reasoning tokens are
  counted as output, as all six bill them; Gemini reports them apart, and they
  are added. A truncated or refused response is its own outcome and still
  carries its usage, because it is still billed. Perplexity's reported cost is
  read as an exact decimal.

  The network policy is ported from the canonical desktop's `provider_retry.rs`:
  - at most two attempts;
  - one retry after a network error, following 1.5 s;
  - `Retry-After` honoured only on HTTP 429, 30 s when absent, capped at 120 s;
  - each attempt's deadline is the lesser of 120 s and the time the session
    has left, which the session passes in; a wait that does not fit in that
    time ends the call with the failure that caused it, instead of starting a
    paid attempt past the limit, and no attempt starts once that time is
    gone, even when a wait that fitted ended late; whether a wait fits is
    decided right before it starts, after the response body has been read;
  - waits, in-flight calls and body reads stop on cancellation.

  **OkHttp never repeats a request on its own.** Every attempt is a paid call,
  and OkHttp retries in several cases outside the two attempts above: HTTP 408
  under `retryOnConnectionFailure`, which is switched off, and HTTP 503 with
  `Retry-After: 0` regardless of that option. Each attempt therefore sends a
  one-shot body, which OkHttp documents it never retries. Redirects are off as
  well: the endpoints are fixed, and on a cross-origin redirect OkHttp drops
  `Authorization` but forwards `x-api-key` and `x-goog-api-key`. A failure while
  reading the body of a response is not retried either: the provider has
  probably already billed. Error messages port the canonical status classes and
  secret redaction, and also redact the exact key of the call, whose shape the
  canonical pattern may not know, so a key echoed in an error body never
  reaches the journal.

  A 2xx object missing the fields its contract requires is an invalid
  response, not an empty success. A response marked complete that carries no
  text, and a refusal from the Responses API, are incomplete outcomes. The
  reason an incomplete outcome carries comes from the provider (`status`,
  `stop_reason`, the refusal text) and goes through the same redaction,
  control-character replacement and length cap as error messages, the exact
  key included — redacted before the cap, so a key at the cut leaves no
  fragment behind. A response body larger than 8 MiB is refused without being
  loaded whole; the largest expected answer, 64 000 output tokens plus JSON and
  sources, stays far below that.

  A pasted key is trimmed. A key with a character that cannot go in an HTTP
  header is refused before any request, as its own outcome: OkHttp would
  otherwise throw with the header value, the key itself, in its message.

  The module has 53 tests against a fake HTTP server; none talks to a real
  provider or carries a key-shaped value. Fifty deliberate mutations each
  make them fail, with a green control run before and after. The per-call
  deadline at maximum effort, and charging the estimate for a call that timed
  out, are recorded as an open item for `:core:sessao` in section 11.
  `THIRDPARTY.md` records the new runtime dependencies, the Public Suffix List
  (MPL-2.0) bundled inside OkHttp, and `kotlin-stdlib`, which the inventory had
  been missing. OkHttp is exported as `api`, because the public constructor
  takes an `OkHttpClient`.

- Raise the Gradle baseline and land `:core:protocolo`, the first module of the
  native port (MAEANDR-14). The project moves to `compileSdk`/`targetSdk` 37 and
  `minSdk` 34 from 36/36/24, gains a `gradle/libs.versions.toml` catalog holding
  only what this change compiles, declares the Kotlin Gradle Plugin — dropping
  the `kotlin-gradle-plugin` line from the Dependabot `ignore` list in the same
  change, as that file itself instructed — and gains a `CI` workflow that runs
  wrapper validation, `assembleDebug`, `lintDebug` and unit tests on every pull
  request. Until now no pull request in this repository was ever compiled.

  `:core:protocolo` is pure Kotlin with no Android dependency, and carries the
  approved-content lock: text segmented into blocks, and a revision allowed to
  change, reorder or add only the blocks it declared in its report's
  `changed_blocks` section, with `protocol_basis`.

  **It is ported from the Rust, not from the web.** `content-lock.ts` declares
  itself in its first line a *"byte-exact port of maestro-app (canonical)
  src-tauri/src/editorial_content_lock.rs"*, and declares a deviation from it —
  keying block equality by normalized text rather than SHA-256. Porting the
  TypeScript would have meant porting a port, inheriting that deviation and
  inventing from scratch a test suite that already exists. The operator decided
  on 21/09/2026 to port from the canonical Rust; its 19 tests come along as the
  module's suite — 14 as they are, 5 extended with the provenance section
  described below — and the specification's claim that every unit comes from
  the web is corrected where it does not hold.

  One trap justified the whole exercise, and it would have failed silently.
  There are **three** different definitions of whitespace in play, measured on
  this project's JDK 17: Java's `Character.isWhitespace` excludes NBSP and NEL;
  Kotlin's `Char.isWhitespace` includes NBSP but still excludes NEL, and adds
  `U+001C`–`U+001F`, which are not whitespace at all in Unicode; and the
  canonical Rust uses Unicode White_Space, which has both NBSP and NEL and not
  the separators. The lock decides whether a revision touched only the blocks it
  declared by comparing whitespace-normalized text, so a different ruler makes
  blocks that the canonical sees as equal look distinct — and the gate would
  approve a revision it should refuse, with nothing on screen. The module
  therefore defines its own Unicode White_Space class and forbids
  `Char.isWhitespace()`, `String.trim()`, `String.isBlank()` and regex `\s`; six
  tests fail if anyone swaps it back, which was proven by swapping it back.

  The same class of trap appears once more and is handled: block character
  counts use code points rather than UTF-16 units, because the count is shown to
  the agents in the manifest.

- Read the agent's revision report with a real JSON parser, and delete the
  hand-written scanner that read it before. This is the repository's first
  production-runtime dependency: `com.fasterxml.jackson.core:jackson-databind`
  2.22.2, recorded in `THIRDPARTY.md` with its two transitives, the two MIT
  components `jackson-core` bundles inside itself, and their measured size.

  The scanner it replaces was 776 lines across two files, and it took 9, 7, 5
  and 6 findings in four consecutive review rounds. The last round's findings
  all reduced to one sentence: a hand-written scanner is not a parser. YAML
  block-scalar indentation, a single quote in the outer scanner, a nested list
  item, `"\u0020"` arriving as the literal text `u0020`, duplicate
  authorization fields unioned instead of refused. That set is not a list of
  holes; it is everything JSON and YAML can express, and enumerating it by hand
  does not converge.

  Two pieces of that surface were invented rather than required. The canonical
  orchestrator cuts the `<maestro_revision_report>` tag and checks its balance
  before the lock runs, so the lock receives isolated content and never free-form
  prose — the prose scanning solved a problem the contract does not have. And
  the canonical prompt asks for "JSON-like audit data", with 19 of 19 fixtures in
  its test suite opening with `{` and no YAML anywhere — the YAML path
  implemented a format nothing ever promised. Both are gone.

  A report that does not parse is now a contract violation, which is what the
  canonical prompt already declares it to be, rather than a reason to fall back
  to the tolerant path where the danger lived. `StreamReadFeature.STRICT_DUPLICATE_DETECTION`,
  which FasterXML documents as disabled by default, closes duplicate `block_id`,
  `protocol_basis` and `change_type` fields with no logic of our own; turning the
  flag off drops exactly four tests, which is how that was proven.
  `DeserializationFeature.FAIL_ON_TRAILING_TOKENS`, also off by default, refuses
  a second document pasted after the first; turning it off drops exactly one. The
  section is read from the top level of the document, so a `changed_blocks`
  nested inside `metadata` stops being reachable by construction instead of
  being caught by an extra check. No canonical rejection changed verdict.

- Require the report to declare where each revised block comes from, as
  `revised_block_origins`, whenever the revised text has a block that is not an
  unchanged copy of a received one. This is a declared departure from the
  canonical contract, decided by the operator on 22/09/2026 and recorded in
  Discussion #41.

  The reason is a question the two texts cannot answer. An edited block leaves
  the received side and comes back with a new hash, indistinguishable from an
  added one: received `A / B / C`, revised `D / B / A-edited / C` fits both "D was
  added and A moved" and "A was edited in place and D was added after B". Three
  pairing heuristics were written and knocked down in three review rounds; a
  five-model cross-review then established that ordinal pairing does not
  identify even when the unmatched counts are equal. The declaration used to
  name only received blocks, so it could not settle it either.

  The ledger has one entry per revised block, in the order of the text; the
  order stands in for a position nobody has to count, and each entry's `prefix`
  is a checksum of that block, not a locator: it must cover at least the first
  20 characters of the block as written, so a literal copy passes whatever its
  spacing. A block whose text equals a received block's text is an unchanged
  copy, however it was produced, as in the canonical: it names a received
  block with that text, the agent choosing among identical ones, and once
  every received block with that text is named, a further copy is an
  addition. Every addition,
  and every piece of a split after the first, carries its own `protocol_basis`
  inside its entry, so a split declaration no longer covers children without
  limit. Extra blocks still need their declaration in `changed_blocks`, as the
  canonical prompt asks, and of the right kind: a received block that
  continues in several revised blocks must declare `split`, and any addition
  needs an entry of the `addition` kind, with its own `protocol_basis`, under
  the ID of a received block, even when the text does not grow on balance; an
  extra exact copy of a received block is an addition, never a split piece; a
  `split` on a block that did not
  split covers nothing. The canonical checks only that some growth entry
  exists (`editorial_content_lock.rs`: `.any(|declaration|
  declaration.allows_block_count_growth)`). Without a ledger this port is
  stricter than the canonical on purpose: each extra block needs its own
  growth entry, because nothing else justifies it block by block.

  With a ledger, each source decides only what it knows. The text decides
  that a block is a copy; the ledger decides which of several identical
  received blocks a copy is, and where every block that is not a copy comes
  from. Nothing attributes by hash against the ledger, which is what the
  earlier review rounds kept finding corners in; and a pure move can no longer
  be declared as two edits.

  Reordering is decided from the declared identities. Between identical copies,
  which copy is which is not a fact of the text, and three review rounds broke,
  one at a time, every rule that tried to infer it. So the ledger's naming
  decides: the IDs the agent gives the copies are its account of which copy went
  where, each received block is named by one unchanged copy only, and whatever
  moved in that account must declare `reorder`. As in the canonical, position is
  compared only among the received blocks the revision keeps: a deleted or added
  block moves nothing by itself, and when two blocks trade places both declare
  `reorder`. The instruction says so. Blocks
  that are not identical copies are flagged the same under any naming of the
  copies. Duplicate content whose
  count changed is another deliberate deviation: the canonical attributes by
  count and can blame the wrong copy. Without a ledger this port refuses. With
  a ledger, it takes the attribution from the ledger. The instruction that asks the agent for the section ships
  in the same module, as `InstrucaoDoRegistro`, so the two ends of the contract
  change together.

  Each of the thirteen guards has its own witness in `MatrizDeDesarmeTest`, and
  the matrix was executed by switching the guards off one at a time: twelve fall
  alone; switching off the thirteenth, position-based reorder detection, also
  takes down the witness of the protocol-basis check on reordered blocks,
  because that check consumes what the detection produces. Twelve existing tests changed verdict — all of them
  approvals that now require the section — and carry an honest ledger; none of
  the canonical rejections changed.

  What it does not close is written down as a test: a ledger can lie. The gain
  is that a move is no longer silent; hiding one takes a false, attributable and
  justified claim.

- Close four further review findings, this time on the correction itself, which
  is the right place for them: three of the four exist only because the previous
  round introduced the text they fault.

  The worst was a handler that collapsed three different failures into one.
  Saying that any failed decryption means "no key configured" would have made an
  expired authentication window — `UserNotAuthenticatedException`, where *"the
  key's validity timed out"* and the key is intact — ask the user to re-enter a
  provider API key that was never lost. The three causes now have three answers:
  expired authentication pauses for authentication,
  `KeyPermanentlyInvalidatedException` admits the secret is gone and asks for the
  key, and an orphaned ciphertext from a restored backup is the only one treated
  as "not configured". `setInvalidatedByBiometricEnrollment(false)` keeps
  enrolling a new fingerprint from costing the user all six keys.

  The second was a sequence the worker cannot execute. Android delivers
  `Service.onTimeout()` to WorkManager's internal
  `androidx.work.impl.foreground.SystemForegroundService`, not to a
  `CoroutineWorker`, so "the worker saves the point and stops the service" was
  impossible as written. What protects the session is not a better callback but
  needing none: the resume point is now written **after every agent turn**, in
  the same transaction as the artifact and the journal entry, so a six-hour cap,
  a job quota, process death or a forced stop each cost at most the turn in
  flight. `onStopped()` and `getStopReason()` — `STOP_REASON_TIMEOUT`,
  `STOP_REASON_QUOTA` — are used to tell the user why it paused, never as the
  place state is saved.

  The third was a test with no declared expectation: the plan required a case at
  the exact cost ceiling while nothing said what equality means, so `<` and `<=`
  would both have satisfied the document and produced different bills. Section
  7.1 now declares the arithmetic it had only promised — scale 8 internally, the
  next-call estimate rounded `CEILING` so rounding never admits a call the
  ceiling could not hold, `HALF_UP` at 2 places for display only, and
  `accumulated + estimate <= max_cost_usd`, equality permitting the call, because
  a ceiling is the most one may spend rather than the first forbidden value.

  The fourth was a contradiction this changelog carried against itself: an
  earlier paragraph still demanded `store: false` in every request while the new
  one correctly said only three providers accept the field. The older sentence is
  now qualified, so no later implementation is directed to reintroduce a field
  three serializers must omit.

- Close the eleven review threads the Codex bot opened on the specification's two
  pull requests, both of which were merged with the threads still open. Every
  claim was checked against official documentation before being accepted; all
  eleven held.

  Two of them contradicted things the document asserted as verified. Android
  grants `dataSync` foreground services *"a total of 6 hours in a 24-hour
  period"*, calls `Service.onTimeout()` at the cap and throws
  `RemoteServiceException` if the service does not stop — the specification had
  said the documentation declared no limit and sent the question to on-device
  measurement, because two pages that omitted the fact were read as its absence.
  And a Keystore key's authorization policy is fixed when it is created —
  *"Once a key is generated or imported, its authorizations can't be changed"* —
  so the offered option of sizing the authentication window per session was not
  a choice to measure but an impossibility; the window is now one fixed value,
  and an expiry mid-session pauses the session for foreground reauthentication,
  since `BiometricPrompt` needs a visible screen and a background worker cannot
  satisfy it alone.

  Two were promises the product could not keep. `android:allowBackup` defaults
  to true and Auto Backup carries `getDatabasePath()` and internal storage, so
  the sessions database and the encrypted secret would have travelled to the
  user's cloud backup; they are now excluded through `dataExtractionRules` in
  both the cloud-backup and device-transfer domains, and a restored ciphertext —
  undecryptable, because the Keystore key does not travel — is treated as "no
  key configured" rather than a crash. And "your key never leaves the device"
  was simply false, since every provider call sends that provider its key as
  authentication; the honest boundary is now written out and is what the
  settings screen will say.

  The rest were gaps in the test plan and one unreliable control surface. The
  plan demanded `store: false` from all six providers although only three expose
  the field, which would have sent Anthropic's `/v1/messages` a parameter it does
  not declare; the process-death test used an in-memory Room database, which
  cannot survive process death and would have passed while proving nothing; the
  cost and runtime ceilings the document calls the only barrier against an
  unexpected bill had no test at all; and the user-authentication gate had none
  either. Finally, denying `POST_NOTIFICATIONS` leaves a foreground service
  running while its notification shows only in Task Manager, so live cost and
  cancellation now belong to the session screen, which the notification mirrors
  rather than replaces.

- Specify the native port, in `docs/especificacao-v1.md`, before any Kotlin —
  the same order `calculadora-android` followed. The document fixes the scope
  unit by unit against the web module it ports (6.674 lines, five times the
  calculadora), the `:core:protocolo` + `:core:provedores` + `:core:seguranca` +
  `:core:sessao` + `:app` split — where the two modules that carry the protocol
  and the six provider contracts stay pure Kotlin, reading the API key through
  an interface so the Keystore implementation can live apart and they remain
  testable on the JVM — and where the deliberation loop runs now that there is no
  Worker: a WorkManager `CoroutineWorker` with `setForeground()` and a `dataSync`
  foreground service, with app start reconciling any session left `running` by a
  killed process.

  It also records three provider-API changes that each invalidate the old way of
  calling, verified in official documentation on 21/09/2026: Anthropic's
  `budget_tokens` is now rejected with HTTP 400 in favour of adaptive thinking;
  Google's Interactions API supersedes `generateContent`, with `thinking_level`
  replacing `thinking_budget` and the two together returning 400; and
  Perplexity's Sonar Chat Completions is switched off on **27/09/2026** in favour
  of the Agent API. One transport serves all six providers — OkHttp over the
  documented REST contracts; the specification first said Retrofit/OkHttp, and
  the operator dropped Retrofit on 23/09/2026 — because no provider publishes an
  Android SDK, a fact measured rather than assumed.

  Three questions the specification could not answer for itself were decided by
  the operator on 21/09/2026 and are written into it as settled: the Keystore key
  is **StrongBox-backed**, with a degradation path that records which of the two
  it ended up on, because most inexpensive devices lack the hardware and
  degrading silently would promise everyone what only some have; user
  authentication gates the key **by time, until the final text is delivered**,
  not per operation, which would mean biometrics on every provider call, dozens
  per session; and the work ships in **four deliveries**, `:core:protocolo` →
  `:core:provedores` → `:core:sessao` → `:app`.

  Two decisions carry consequences worth naming. The user's API keys live on the
  device, by the operator's decision, encrypted by a non-exportable Android
  Keystore key, which is what makes the on-device orchestration mandatory rather
  than merely convenient. And `store: false` is explicit in every request **to
  the three providers whose APIs expose the field**, and the field is absent from
  the other three, because OpenAI defaults to retaining responses for 30 days and
  Gemini for 55 on the paid tier — a default that would contradict the product's
  own premise — while Anthropic's `/v1/messages` does not declare `store` at all.
  Serialization is per provider; there is no single retention flag stitched
  across all six.

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

- Complete `:core:protocolo` with the rest of MAEANDR-14:
  - the serial turn's output contract, with the `MAESTRO_STATUS` line reader;
  - the bibliographic integrity gate;
  - the anti-impoverishment quality guard;
  - prompt assembly;
  - cost in `BigDecimal`.

  Each unit comes from where it is canonical, a question the specification
  requires be asked per unit. The turn contract, the gate and the guard come
  from the desktop's Rust, which the web declares it ports byte for byte. The
  prompts and cost come from the web, which has the API-only prompt variants and
  the three per-provider rates, where the desktop builds CLI prompts and has no
  per-request rate.

  The turn contract refuses:

  - a reply that echoes the prompt;
  - unbalanced report or final-text tags; a balanced duplicate resolves to the
    last complete block;
  - a report that is not exactly one strict JSON object, read by the same
    parser configuration as the lock, so no report can pass one path and fail
    the other;
  - a final text without `custody: "revised"`, or `revised` custody without a
    final text;
  - an `unchanged` turn that still lists correctable changes;
  - a final text that still carries `[EVIDENCIA_PENDENTE]` or a bibliographic
    lacuna such as `[s. d.]`.

  The gate treats only ASCII `0`–`9` as digits, as the Rust does; Kotlin's
  `isDigit()` would turn `[٣?]` into an uncertain date.

  The revision prompt describes this repository's lock, not the web's. It
  carries the `revised_block_origins` instruction the lock requires. It does not
  ask for `new_block_count`, which the web prompt requests and this lock never
  reads. It also drops the web's claim that public links are audited at
  finalization. That audit arrives with MAEANDR-18, and until then the agent
  must not rely on a check that does not exist.

  Cost follows section 7.1, clarified by the operator on 23/09/2026:

  - estimates and observed costs are summed and compared at scale 8, both
    rounded up;
  - a call is allowed when `accumulated + estimate <= cap`;
  - two decimals, half up, are for display only;
  - a missing input or output rate refuses the call instead of producing `NaN`;
  - a negative token count from the provider counts as missing and falls back
    to the estimate, so a malformed response cannot lower the running total.

  Summing at two decimals, as the old wording read, would have made a $0.004
  call add nothing, and the cap would never trip.

  The lock stops accepting `changes` as an alias for `changed_blocks`. Since the
  desktop's v00.05.65, `changes` is the list of changed passages that the turn
  contract reads; with the alias, one list would count as both. The 19
  canonical lock tests still pass without it.

  The canonical Rust cases for these units are ported where they exercise them;
  those that need the final-release audit move to MAEANDR-18. The suite grows
  from 117 to 188 tests. Twenty-one deliberate mutations, one per new rule, each
  make it fail, and a green control run before and after the mutations proves
  the failures come from the tests and not from the harness.

### Changed

- Align the approved-content lock with the canonical lock's v00.05.65 contract
  (MAEANDR-17). The desktop rewrote its lock in `maestro-app#395` and `#396`
  without adopting this repository's `revised_block_origins` ledger. The 33 test
  cases it gained were run against the Kotlin lock, each as an agent following
  this app's prompt would answer: with a truthful ledger.

  **Adopted**, because they are about the report's form and do not touch the
  ledger. Each one was previously looser here and now refuses:
  - an empty or non-object report, on every turn;
  - a field name in another case: `Changed_Blocks` is no longer
    `changed_blocks`;
  - an entry that is not an object, or has no string `block_id`;
  - a malformed `block_id`, with no trimming or case folding — and the same
    for the ledger's `origin`, which carries the same manifest ID;
  - a `block_id` absent from the received manifest;
  - an empty or repeated `change_type`, or one that is neither a string nor a
    list of strings;
  - any `change_type` token other than the exact `addition`, `split` and
    `reorder`: synonyms such as `moved` and comma-joined values such as
    `"edit, reorder"` no longer authorize anything;
  - a `protocol_basis` whose only leaves are numbers or booleans.

  The serial turn checks the same form through the same reader, even when no
  revised text is returned — slightly stricter than the canonical, whose turn
  only type-checks and leaves the rest to the lock. Messages now use the
  canonical wording, and a malformed report is refused before duplicate-block
  ambiguity is considered, as in the canonical order. The revision prompt now
  tells the agent the same rules: copy every block ID exactly, and only the
  exact tokens grant permission.

  **Covered by the ledger, not adopted.** The local-growth-source rule, the
  `new_block_count` limit and the two #396 cases (MAESTRO-31, growth beside
  several edited blocks) exist because the desktop cannot tell where an extra
  block came from. Here the ledger declares it, each addition carries its own
  `protocol_basis`, and `new_block_count` is ignored. The residual is the one
  accepted in Discussion #41: the declaration can lie.

  **Stricter here by design.** The desktop lets an edited block change place
  without `reorder`, because it cannot see the move. The ledger can, and this
  lock requires `reorder` there.

  Suite: 226 tests. Thirteen mutations, one per adopted rule, each make it
  fail, with a green control run before and after.

- Reconfirm `grok-4.7` against xAI's official documentation on 22/09/2026, when
  the operator announced its release, and record in the specification the
  reasoning-effort values the table was missing for it: `low`, `medium`, `high`
  (the default) and `xhigh`. The model itself does not change — the
  specification already named `grok-4.7` on 21/09. xAI's release notes group
  entries by month only, so they cannot show whether it was already published on
  that date; the specification says so rather than claiming it.

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
