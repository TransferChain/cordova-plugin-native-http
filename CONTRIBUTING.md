# Contributing to cordova-plugin-native-http

Contributions to this standalone Cordova plugin are welcome: bug reports,
documentation improvements, focused fixes and reproducible platform tests.

## Before you start

Read [the README](README.md), [public API](docs/API.md),
[development guide](DEVELOPMENT.md) and [test setup](docs/TESTING.md). Discuss
breaking API changes, cryptographic or storage-format changes, new platforms and
new dependencies in an issue before implementation. Small fixes can be proposed
directly as a pull request.

Report vulnerabilities through [SECURITY.md](SECURITY.md), not a public bug
report containing exploit details. If private reporting is unavailable, ask
maintainers for a private channel without disclosing the vulnerability.

## Bug reports

Include the plugin version or commit, Cordova/platform versions, OS and device
or emulator, reproduction steps, expected behavior and actual behavior. Use a
minimal test host and synthetic data. Include only sanitized error codes and
relevant logs; exclude keys, passwords, tokens, private data and signing files.
State whether the failure occurs on Android, iOS or a host mock.

## Local development

1. Fork and clone the plugin repository, then create a focused branch.
2. Use Linux for Android/host development or macOS with Xcode for iOS. Follow
   the toolchain and harness requirements in [TESTING](docs/TESTING.md).
3. Install your local checkout in a disposable Cordova test host. From that
   host's root, for example:

   ```sh
   cordova plugin add /absolute/path/to/cordova-plugin-native-http
   ```

4. Refresh the installed plugin copy after source changes and rebuild the
   affected platform. Confirm the test host is using your edited source.

The repository supplies plugin/test sources; do not assume a standalone native
test project or package-level test runner exists. Install development tools with
pnpm where needed. Do not add a dependency solely to run an unrelated
application framework. Keep generated platforms, node_modules, build outputs,
logs and local credentials out of pull requests.

## Implementation conventions

Keep changes focused and preserve the public Cordova module/global, action
names, argument order, result types and error codes unless a breaking change has
been explicitly agreed. Keep plugin.xml synchronized with source changes. The
plugin must remain usable without private application services or stores.

- Use JavaScript for the bridge and follow the surrounding native style. Write
  developer explanations in English, emphasizing contracts and ownership.
- Use two-space JavaScript indentation, single quotes and no semicolons. Always
  parenthesize arrow parameters: `(value) => value`.
- Use `typeof value === 'undefined'` or `typeof value !== 'undefined'` for
  undefined comparisons. Preserve assignments and null semantics.
- Wrap Markdown prose at 80 columns. Formatter settings are printWidth 80,
  proseWrap always, embeddedLanguageFormatting off and arrowParens always. Do
  not reformat unrelated files or vendored code.
- Explain new dependencies, their platform compatibility and license impact.
  Retain existing third-party notices and provenance records.

## Security and resource ownership

Never log secrets or add weaker fallback behavior after an error. Validate
untrusted bridge inputs at the native boundary as well as the JavaScript API.
Keep native work and callback/lifecycle synchronization consistent with the
implementation guide.

Use owned byte buffers for secrets where the API permits. Clear owned temporary
buffers in finally after asynchronous/native use has completed, including
failure paths. Do not wipe caller-owned views, shared storage or live return
values. Release listeners, timers and native resources at their lifecycle
boundary. Immutable strings and provider/bridge copies cannot be promised to be
fully erased by JavaScript cleanup.

Plugin-specific review and regression coverage:

- Preserve normal OS trust and hostname verification alongside optional
  certificate/SPKI pins. Never add trust-all or unpinned retry paths.
- Cover incorrect pins, trusted-chain matching, malformed requests, body/header
  limits and exactly one terminal callback.
- For lifecycle changes, cover cancellation, reset, admission rejection, stalled
  I/O, owned-body wiping and executor shutdown. Keep redirects caller-controlled
  and document only the plugin's callback transport.

## Validation and pull requests

Run the relevant checks described in [TESTING](docs/TESTING.md), one job at a
time. Add focused regression coverage for behavior changes, including failure
and ownership paths where relevant. Documentation-only changes need link,
example and formatting review rather than an unrelated native build.

In your pull request, include:

- The problem, reproduction and resulting behavior.
- Public API, compatibility, dependency or persisted-format implications.
- Tests actually run, exact commands, toolchain/platform details and results.
- Tests not run or skipped, with the reason and remaining verification needed.
- Updated API/development documentation when the contract changes.

Distinguish host mocks, compilation, emulator/simulator execution and physical
hardware results. Test preparation is not execution. iOS testing is ongoing;
macOS/Xcode evidence is required before claiming an iOS change verified.
Contributions that cannot run both platforms should state that limitation so
maintainers can arrange the remaining checks. Never mark skipped biometrics or
unexecuted native tests as passed.

Keep review discussion constructive and respond to reproducible findings.
Maintainers handle merge, versioning and publication; opening a pull request
does not publish a release. Preserve the [MIT license](LICENSE), contribute only
material you have the right to submit and identify third-party material with its
required attribution.
