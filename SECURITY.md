# Security Policy

## Supported versions

Only the latest `main` and the most recent tagged release receive security updates. Older tags are best-effort.

| Version | Supported |
|---|---|
| `main` | yes |
| latest `v*.*.*` tag | yes |
| anything older | no |

## Reporting a vulnerability

Please **do not file public GitHub issues** for security problems.

Use one of these private channels:

1. **GitHub Security Advisories (preferred):** <https://github.com/Samkaypro/pinoRAG/security/advisories/new>
2. **Email:** the maintainer listed in `.github/CODEOWNERS` (find their public email on their GitHub profile).

Include:
- A short description of the issue.
- Steps to reproduce, or a proof-of-concept.
- The affected version (git sha or tag).
- Any relevant logs or payloads (redact secrets).

## Response targets

- Acknowledgement within 3 business days.
- Initial assessment within 7 business days.
- Fix or mitigation plan within 30 days for High and Critical issues.
- Public disclosure coordinated with the reporter after a fix ships.

## Scope

In scope:
- The Spring Boot application under `/pinoRAG`.
- Provided Docker image and Compose stack.
- Default configuration and documented deployment patterns.

Out of scope:
- Issues that require physical access to the host.
- Vulnerabilities in third-party dependencies that already have a public CVE and an upstream fix; please open a normal issue or PR to bump the version.
- Social engineering of maintainers.

## Safe harbor

We will not pursue legal action against researchers who:
- Report in good faith via the channels above.
- Avoid privacy violations, service disruption, or data destruction.
- Give us reasonable time to fix the issue before public disclosure.
