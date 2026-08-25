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

- After the initial empty-repository seed, every change to `main` uses a pull
  request and GitHub's native merge queue. Squash is the only merge method.
- Human-authored pull requests require explicit admission unless the operator
  grants a scoped exception. Canonical Dependabot pull requests may be handled
  by the central custom controller only after the exact head satisfies the same
  rules and checks.
- No workflow bypasses rulesets or performs a direct merge.
- Set workflow-level permissions to `{}` and grant each job only the token
  capabilities it demonstrably needs.
- Pin external GitHub Actions to immutable full commit SHAs and regenerate
  `.github/workflows/actions.lock` after any workflow dependency change.
- Do not commit secrets, tokens, private keys, signing material,
  `local.properties`, service-account files, user content, production
  payloads, or real infrastructure identifiers.

## Validation

Before opening or updating a pull request:

1. validate edited workflows with `gh actions-lock` and Zizmor;
2. confirm that checks intended for the merge queue also run on
   `merge_group` with the same context name;
3. run only checks that apply to the current repository state;
4. record exact validation evidence in the pull request and linked work item.

Do not create a fake Gradle project or execute Android build gates before a
real application scaffold exists.

## Security and conduct

Use the private reporting path in [SECURITY.md](SECURITY.md) for security
matters. By participating, you agree to
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
