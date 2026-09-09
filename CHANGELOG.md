# Changelog

All material changes to Maestro Android are recorded here.

## [Unreleased]

### Changed

- Aligned this pre-application repository with the fleet's native governance:
  GitHub CodeQL Default setup, pull-request Dependency Review and Pages checks,
  direct Scorecard SARIF, and Zizmor without retired merge-queue events.
- Standardized GitHub Actions Dependabot updates to Monday 06:00 in
  `America/Sao_Paulo`, with grouped minor/patch updates, separate major updates,
  and the existing selective seven-day cooldown. Added repository-local native
  auto-merge subject to the effective GitHub rules and checks.
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
