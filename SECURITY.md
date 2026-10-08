# Security policy

## Reporting a vulnerability

Do not post credentials, recovery phrases, private keys or exploitable details
in a public issue. Use the repository's private security advisory reporting
when enabled. If no private channel is configured, ask the maintainers for
one without including vulnerability details. A dedicated security contact
and response SLA have not yet been declared.

Include the plugin version, operating system, minimal synthetic reproduction,
expected and actual behavior, and impact. Use disposable test data. Never
attach production database, secure-storage exports or signing credentials.

## Scope and expectations

Relevant issues include native bridge validation, unsafe buffer handling,
cryptographic misuse, concurrency, lifecycle cleanup and unauthorized access.
Native providers and Cordova may make internal copies; clearing owned buffers
is not a guarantee that all runtime memory has been erased.

Only tested platform/version combinations should be advertised as verified.
There is no declared long-term support matrix or guaranteed response time.
Before release, maintainers must review dependency notices, run the relevant
platform tests and configure a private vulnerability reporting channel.
