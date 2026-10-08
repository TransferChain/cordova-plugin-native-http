# Usage and API

All examples run inside an async function after Cordova `deviceready`. They call
the public JavaScript module registered by plugin.xml. Consumer functions such
as consumeKey are placeholders supplied by the caller.

## Low-level interface

This plugin deliberately does not create a global fetch or global HTTP object.

```js
const http = cordova.require('cordova-plugin-native-http.UltraHTTP')
http.request({
  id: 'example-request',
  url: 'https://example.com/',
  method: 'GET', headers: {}, fingerprints: [], hasBody: false
}, new ArrayBuffer(0), (metadata, body) => {
  // metadata contains status, url and headers; body is an ArrayBuffer.
  consumeResponse(metadata, body)
}, (error) => {
  handleFailure(error)
})
// Cancel while the request is pending when its owner no longer needs it:
// http.cancel('example-request')
```

Callbacks and request IDs are owned by the application. This is a buffered
callback API. A successful callback receives metadata and an ArrayBuffer; it
does not return a browser Response object or a Promise.

Use HTTPS URLs. Empty fingerprints retain normal OS CA/hostname validation; they
do not disable TLS verification. Native pins are canonical certificate:<64 hex
digits> or spki:<64 hex digits>. Provide independently trusted expected pins,
including rotation pins where needed. Native redirects are not automatically
followed; a caller implementing redirects must validate each hop, reapply pins
and prevent forwarding credentials across origins.

## Errors and lifecycle

Handle rejected promises or failure callbacks explicitly. Do not substitute
plaintext, predictable keys or weaker algorithms after failure. Keep caller
buffers valid until async work completes, and wipe only buffers you own.

[Developer guide](../DEVELOPMENT.md) · [Security policy](../SECURITY.md) ·
[README](../README.md)
