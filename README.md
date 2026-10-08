# cordova-plugin-native-http

Native HTTP transport with optional SHA-256 certificate/public-key pinning.

## Installation

Install from the TransferChain GitHub repository:

```sh
cordova plugin add https://github.com/TransferChain/cordova-plugin-native-http
```

After deviceready, use cordova.require('cordova-plugin-native-http.UltraHTTP').
This package targets Android and iOS; it does not provide a browser fallback.
The command targets the GitHub repository; repository publication is managed
separately. This document does not imply an npm release is available.

## iOS validation status

iOS testing is ongoing. Progress and validation updates will be shared
regularly. XCTest sources are available, but macOS/Xcode execution has not yet
been completed; verified iOS security behavior is not claimed.

## Documentation

- [Usage, parameters and examples](docs/API.md)
- [Tests and verification](docs/TESTING.md)
- [Native implementation guide](DEVELOPMENT.md)

## Plugin entry point

After Cordova's `deviceready` event, load the registered module with
`cordova.require('cordova-plugin-native-http.UltraHTTP')`. Its callback API
returns response metadata and binary bytes; see [API examples](docs/API.md).

## API

```text
request(options, body, success, failure), cancel(id)
```

See [the developer guide](DEVELOPMENT.md) for argument details, native
implementation, lifecycle, failure behavior and platform prerequisites. Binary
inputs use Cordova's bridge; callers must respect documented ownership and avoid
mutating inputs while an operation is pending.

## Tests and development

Plugin test sources live under tests/. See [test setup](docs/TESTING.md) for the
suites included in this package and their harness requirements. Native checks
require the Android or Xcode toolchain. A host mock passing is not evidence that
Android or iOS device execution succeeded.

## Contributing

Read the [contribution guidelines](CONTRIBUTING.md) for development setup,
coding conventions, regression tests and pull request expectations.

## Security

Recorded security-related regression results:

- Android emulator, 2026-10-06: 9 transport/pinning and concurrency cases passed
  with no failures or skips. Coverage included native HTTPS, wrong-pin
  rejection, platform trust/hostname checks, admission limits, cancellation,
  reset/rejected-dispatch races and executor shutdown.
- Native owned-body wiping and caller-input preservation passed the
  cancellation/admission regressions.
- Host TLS: 14 checks passed using disposable CA fixtures, including trust,
  hostname, certificate/SPKI pins and rotation. Redirect orchestration is
  caller-owned and is not a plugin security feature.

These are results from the dated validation runs, not a new execution for this
documentation update. Android results are emulator results; they do not
establish physical-device or iOS validation. Host mocks do not prove native
execution. Passing regressions are not a security audit or certification. See
[test setup and scope](docs/TESTING.md).

Read [SECURITY.md](SECURITY.md) to report a vulnerability. Never include
production credentials or secret values in reports or logs.

## License

[MIT](LICENSE), TransferChain AG. Dependencies and vendored components retain
their respective licenses and attribution requirements.
