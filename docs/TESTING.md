# Test layout and verification

Paths below are relative to the plugin root. Run tests in a disposable Cordova
test host containing this plugin; never use production credentials.

## Host TLS tests

The Java suite in tests/native/HTTPPolicyTest.java requires FingerprintMatcher,
generated disposable CA/leaf certificates and local TLS servers. Its main
arguments are a certificate directory followed by the fixture endpoint URLs;
inspect the test source when wiring a harness. No standalone fixture generator
or package test runner is bundled here.

## Android and iOS

- tests/android/ contains Android instrumentation sources. Configure the test
  host with Cordova, the plugin and AndroidX test dependencies, and add these
  sources to its androidTest source set. Build and execute the selected
  instrumentation suite on an emulator or device.
- tests/ios/ contains XCTest sources and test support. Configure a Cordova test
  host and the required support module in Xcode, include the plugin sources and
  run the selected suite on macOS with a simulator or device.

The package provides test sources, not a standalone Gradle/Xcode test project or
application-level runner commands. Check the imports and support files when
configuring your host. Compilation and test-source preparation are not proof of
execution. Run validation jobs serially.

See [README security results](../README.md#security) for recorded validation and
its limits, and [DEVELOPMENT.md](../DEVELOPMENT.md) for native invariants.
