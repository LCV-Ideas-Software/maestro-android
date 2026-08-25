# Security Policy

## Supported state

Only the current `main` branch is maintained. This repository presently
contains public governance, static documentation, automation, and an inert Code
Quality probe; it does not contain an Android application or production
runtime.

## Reporting a vulnerability

Do not disclose a vulnerability, credential, signing artifact, personal datum,
private editorial material, or unpublished operational detail in a public
Issue, Discussion, pull request, commit, or Pages site.

Report privately to `lcv@lcv.dev` with:

- the affected repository and revision;
- a concise impact and exploitability description;
- reproducible steps or a safe proof of concept that exposes no secrets;
- any safe mitigation already tested.

Never send live credentials. Revoke or rotate exposed credentials immediately
through their owning system and report only non-secret metadata.

## Scope

In scope: repository automation, dependencies and supply-chain configuration,
publication boundaries, security documentation, the Pages surface, and future
application code committed here.

Out of scope: social engineering, physical attacks, denial-of-service testing
without prior written authorization, spam, noisy automated scanning, and
reports based only on outdated dependencies without a concrete vulnerable path.

## Coordinated disclosure

LCV Ideas & Software will triage reports privately and coordinate remediation
before public disclosure. Public disclosure should wait until a fix or
mitigation is available unless there is an immediate user-safety reason.
