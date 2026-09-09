# Contributing to Maestro Android

This public repository is the future Android edition of Maestro Editorial AI.
Contributions must preserve its privacy boundaries and the repository's
security and release gates.

## Tracking boundary

- Link a GitHub Issue to Linear only when both resources are explicit and
  unequivocal counterparts.
- Never copy sensitive Linear content, private Project drafts, credentials,
  user material, or unpublished operational details into public Issues,
  Discussions, commits, pull requests, or Pages.
- Never create speculative Issues to satisfy a reconciliation count.

## Change control

- Every change to `main` uses a pull request and GitHub's native squash merge.
  There is no merge queue.
- Human-authored pull requests require explicit admission unless the operator
  grants a scoped exception. Before the first or any supplemental push, present
  the complete proposed changes for the operator's approval. GitHub settings
  changes require separate prior explanation and explicit approval.
- The organization has one human operator; do not add a mandatory self-review.
  The repository-local Dependabot workflow enables native auto-merge for the
  exact pull-request head, subject to GitHub's effective rules and checks.
  No workflow bypasses rulesets or controls another repository.
- Use official native capabilities. Customization requires the operator's
  explicit prior authorization; do not recreate retired Actions lock mechanisms
  or central controllers. Dependency-manager lockfiles are distinct.
- Set workflow-level permissions to `{}` and grant each job only the token
  capabilities it demonstrably needs.
- Pin external GitHub Actions to immutable full commit SHAs directly in each
  workflow.
- Do not commit secrets, tokens, private keys, signing material,
  `local.properties`, service-account files, user content,
  or production payloads. Nonsecret resource identifiers and required native
  configuration metadata may be versioned; secret values must not be.

## Validation

Before opening or updating a pull request:

1. validate edited workflows with the official Actionlint and Zizmor tools;
2. confirm that native Pages artifact, Dependency Review, and Zizmor checks
   cover pull requests, including retargeting to `main`;
3. run only checks that apply to the current repository state and preserve the
   static site's public-content and no-indexing boundaries;
4. record exact validation evidence in the pull request and linked work item.

Do not create a fake Gradle project or execute Android build gates before a
real application scaffold exists.

## Inbound rights

Opening a contribution does not transfer copyright. Follow [INBOUND.md](INBOUND.md)
for ownership verification and any required separately executed written inbound
license or assignment before merge. The repository's AGPL-3.0-or-later license
and third-party license grants remain unchanged.

## Security and conduct

Use the private reporting path in [SECURITY.md](SECURITY.md) for security
matters. By participating, you agree to
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
