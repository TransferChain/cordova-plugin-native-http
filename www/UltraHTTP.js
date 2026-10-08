const exec = require('cordova/exec')

module.exports = Object.freeze({
  request(options, body, success, failure) {
    // Cordova owns binary serialization. Do not add a Base64 transport layer.
    exec(success, failure, 'UltraHTTP', 'request', [options, body])
  },
  cancel(id) {
    exec(
      () => {},
      () => {},
      'UltraHTTP',
      'cancel',
      [id]
    )
  }
})
