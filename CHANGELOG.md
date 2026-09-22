# Changelog

All material changes to Maestro Android are recorded here.

## [Unreleased]

### Added

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
  of the Agent API. One transport serves all six providers — Retrofit/OkHttp over
  the documented REST contracts — because no provider publishes an Android SDK,
  a fact measured rather than assumed.

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

### Changed

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
