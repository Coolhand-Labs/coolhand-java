# Security Policy

## Supported Versions

Only the latest published version of `coolhand-java` on Maven Central is supported with security
fixes. Please upgrade to the latest version before reporting an issue.

## Reporting a Vulnerability

Please do **not** open a public GitHub issue for security vulnerabilities.

Instead, report vulnerabilities privately using one of the following:

- [GitHub Security Advisories](https://github.com/Coolhand-Labs/coolhand-java/security/advisories/new)
  for this repository (preferred)
- Email team@coolhandlabs.com

Please include a description of the vulnerability, steps to reproduce, and the impact you believe
it has. We aim to acknowledge reports within 3 business days and to provide a fix or mitigation
plan within 30 days, depending on severity.

## Scope

Of particular interest: credentials (provider API keys, OAuth tokens, the Coolhand API key) that
reach a submitted log or an exception message despite the redaction described in
[docs/global-monitoring.md](docs/global-monitoring.md#what-is-redacted), and any way an
integration changes what the host application sends or receives.
