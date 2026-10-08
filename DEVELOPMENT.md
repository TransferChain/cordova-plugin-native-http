# Native HTTP development guide

This plugin implements a buffered HTTPS transport with Android
`HttpsURLConnection` and iOS `URLSession`. It adds no third-party HTTP client.
After deviceready, load
`cordova.require('cordova-plugin-native-http.UltraHTTP')`. The module exposes
request and cancel. It does not intercept WebView or other SDK traffic. See
[usage examples](docs/API.md).

## Request contract and ownership

`UltraHTTP.request(options, body, success, failure)` accepts an options object
and a Cordova ArrayBuffer. Options contain `id`, canonical `url`, uppercase
`method`, string `headers`, normalized `fingerprints` and `hasBody`. The native
reply is Cordova multipart: JSON `{status, url, headers}` followed by an
ArrayBuffer. Neither side implements another Base64 wire codec. Native
instrumentation may use Cordova's internal encoded arguments because it bypasses
the actual JS bridge.

URLs may address any HTTPS host or TLS port. URL credentials and insecure/local
file schemes are rejected. This transport does not expand the main WebView's
CSP, navigation allowlist or external-link policy. Callers decide which service
may receive their initial Authorization header/body; unrestricted transport is
not an origin authorization mechanism.

Methods: GET, POST, PUT, DELETE, HEAD, PATCH and OPTIONS. GET/HEAD bodies
reject. Supply the body as an ArrayBuffer and set hasBody consistently.
Serialization, response decoding and higher-level retry policies belong to the
caller. The plugin is a buffered callback transport.

## TLS and fingerprints

Empty pins mean ordinary OS CA and hostname validation. Pins add a restriction;
they cannot approve an otherwise invalid TLS connection. No trust-all mode,
self-signed bypass, preflight probe, TOFU or WebView fallback exists.

Accepted pin formats at the native boundary:

| Format                      | Meaning                             |
| --------------------------- | ----------------------------------- |
| certificate:<64 hex digits> | SHA-256 of complete certificate DER |
| spki:<64 hex digits>        | SHA-256 of DER SubjectPublicKeyInfo |

Supply at most 16 pins in canonical form. A matching pin in the verified chain
is sufficient. Scope pins to the intended destination. Include old and new pins
during rotation, then retire old pins deliberately. Expected pins must come from
an independently trusted source.

Android wraps the OS X509TrustManager with X509TrustManagerExtensions and
matches only its cleaned trusted chain. The extension's host parameter selects
host trust policy; actual hostname matching is a separate HttpsURLConnection
default verifier, which remains untouched. iOS evaluates SecTrust with an SSL
hostname policy and matches SecTrustCopyCertificateChain. SPKI is extracted from
bounded DER fields, not SecKey's algorithm-dependent raw external key
representation.

## Redirect handling

Native automatic redirects are disabled; 3xx responses reach the success
callback. Following a Location header requires a new request. A caller that
implements redirects must bound hops, validate each target and apply its pin
policy to every exchange. Do not forward credentials or secret bodies to an
unapproved origin.

## Concurrency and cleanup

Android admits at most 32 jobs and dispatches blocking I/O on Cordova's thread
pool. A ConcurrentHashMap tracks ownership; AtomicBoolean allows exactly one
terminal callback across success, cancel, reset and failure. Every run
disconnects and clears owned body/response/chunk buffers in finally. Pool
admission failure removes the job and clears its body. Disconnect is scheduled
away from bridge admission because it can block on a socket operation.

iOS uses an ephemeral session for each request with cookies, credential storage
and cache disabled. NSLock protects registration; the serial OperationQueue owns
each job's delegate state. Registration happens before cancellation can overtake
it. Both task-level and session-level trust challenges use the same validator.
Completion invalidates the session and clears accumulated owned Data.

Caller-owned inputs and returned response bodies remain the caller's
responsibility. OS networking, Cordova serialization, JS strings and provider
copies are outside the owned-buffer wipe contract; this is not a claim that
every process memory copy is erased.

Bounds: 8 MiB request/response, 64 request headers, 32 KiB header content, 32
native jobs, 30-second timeout per hop. OS parsing may allocate before plugin
bounds are checked. This interface is not intended for large file transfer.

## Errors and verification

Native codes: 1 invalid argument; -1 network failure; 88 TLS/pin failure; 90
abort; 91 resource limit. Native errors never include URL query, headers, token
or underlying TLS details. HTTP 4xx/5xx remain responses, not transport errors.

[Host TLS tests](tests/native/HTTPPolicyTest.java) require generated test
certificates and local TLS endpoints. Android instrumentation and iOS XCTest
sources are included under tests/android and tests/ios. See
[test setup](docs/TESTING.md) for the harness boundary. Refresh the Cordova
plugin installation and rebuild the test host after native changes.

Tests must cover ordinary trust, hostname verification, wrong pins, trusted
chain matching, cancellation and resource limits. Host tests do not replace
platform trust-provider tests; compilation does not prove execution.

## Parallel regression suite and cancellation design

The Android HTTPParallelTest suite exercises the actual plugin with eight
overlapping HTTPS jobs and different request-owned pin policies. It also fills
admission, races cancel/reset against four stalled TLS handshakes plus queued
work, checks actual socket EOF and native owned-body zeroing, then checks both
request and cleanup executor termination. A repeated race test runs eight
additional rounds.

Do not move disconnect back to the request pool: a saturated pool cannot run the
cleanup tasks needed to free its blocked workers. Android uses two lazy
dedicated cleanup workers, owned by this plugin; destroy shuts them down. The
terminal compare-and-set must guard the entire cancel scheduling path, not just
the callback, so repeated cancellation cannot enqueue duplicate work. iOS
URLSession owns network I/O separately from each serial delegate queue; the
added native parallel XCTest still needs execution on macOS/Xcode.

Executor rejection is another terminal transition: reset may already have sent
an abort while admission is blocked. Rejection must therefore use the same CAS.
Destroy sets the closed flag under the admission lock before reset/cleanup
shutdown; this forbids a new request from slipping past the cleanup snapshot.
The deterministic reset/rejection and post-destroy admission tests protect both.
