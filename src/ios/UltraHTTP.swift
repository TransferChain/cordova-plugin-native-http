import Foundation
import CoreFoundation
import Security
#if canImport(Cordova)
import Cordova
#endif

@objc(UltraHTTP)
final class UltraHTTP: CDVPlugin {
    private let lock = NSLock()
    private var jobs: [String: (generation: UUID, job: HTTPJob)] = [:]

    @objc(request:)
    func request(_ command: CDVInvokedUrlCommand) {
        // Admission stays synchronous so cancel cannot overtake registration.
        // URLSession performs TLS and network I/O on its own background queues.
        do {
            guard command.arguments.count == 2,
                  let options = command.arguments[0] as? [String: Any],
                  let body = command.arguments[1] as? Data,
                  let id = options["id"] as? String, !id.isEmpty, id.count <= 64 else {
                throw HTTPFailure.invalid
            }

            let generation = UUID()
            let job = try HTTPJob(options: options, body: body) { [weak self] metadata, data, code in
                guard let self else { return }

                self.lock.lock()
                // A reset can reuse a JS id before an old callback drains.
                // Remove only this registration, never the replacement job.
                if self.jobs[id]?.generation == generation {
                    self.jobs.removeValue(forKey: id)
                }
                self.lock.unlock()
                if let metadata, let data {
                    let result = CDVPluginResult(status: CDVCommandStatus_OK,
                                                 messageAsMultipart: [metadata, data])

                    self.commandDelegate.send(result, callbackId: command.callbackId)
                } else {
                    self.fail(command, code ?? -1)
                }
            }

            self.lock.lock()
            let allowed = self.jobs.count < 32 && self.jobs[id] == nil

            if allowed { self.jobs[id] = (generation, job) }
            self.lock.unlock()
            guard allowed else { throw HTTPFailure.limit }

            job.start()
        } catch let failure as HTTPFailure {
            self.fail(command, failure.code)
        } catch {
            self.fail(command, 1)
        }
    }

    @objc(cancel:)
    func cancel(_ command: CDVInvokedUrlCommand) {
        guard let id = command.arguments.first as? String else {
            fail(command, 1)
            return
        }

        lock.lock()
        let job = jobs[id]?.job

        lock.unlock()
        job?.cancel()
        commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_OK), callbackId: command.callbackId)
    }

    override func onReset() {
        lock.lock()
        let current = jobs.values.map { $0.job }

        jobs.removeAll()
        lock.unlock()
        for job in current { job.cancel() }
    }

    private func fail(_ command: CDVInvokedUrlCommand, _ code: Int) {
        commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_ERROR,
            messageAs: ["code": code, "message": "Native HTTPS request failed"]),
            callbackId: command.callbackId)
    }
}

private enum HTTPFailure: Error {
    case invalid, limit

    var code: Int { self == .limit ? 91 : 1 }
}

// One ephemeral URLSession per request isolates pins/cookies/cache and makes
// cancellation ownership explicit. Delegates serialize response accumulation.
private final class HTTPJob: NSObject, URLSessionDataDelegate, URLSessionTaskDelegate {
    private static let maxBytes = 8 * 1024 * 1024
    private let queue: OperationQueue
    private let request: URLRequest
    private let pins: CertificatePins
    private let completion: ([String: Any]?, Data?, Int?) -> Void
    private var session: URLSession?
    private var data = Data()
    private var response: HTTPURLResponse?
    private var terminal = false
    private var failureCode: Int?

    init(options: [String: Any], body: Data,
         completion: @escaping ([String: Any]?, Data?, Int?) -> Void) throws {
        guard let text = options["url"] as? String, let url = URL(string: text),
              url.scheme == "https", let host = url.host, !host.isEmpty,
              url.user == nil, url.password == nil,
              let method = options["method"] as? String,
              ["GET", "POST", "PUT", "DELETE", "HEAD", "PATCH", "OPTIONS"].contains(method),
              let hasBody = options["hasBody"] as? Bool,
              !hasBody || (method != "GET" && method != "HEAD"),
              body.count <= Self.maxBytes,
              let headers = options["headers"] as? [String: String], headers.count <= 64,
              let values = options["fingerprints"] as? [String] else { throw HTTPFailure.invalid }

        var outgoing = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        var headerBytes = 0

        outgoing.httpMethod = method
        outgoing.httpShouldHandleCookies = false
        outgoing.httpBody = hasBody ? body : nil
        for (name, value) in headers {
            headerBytes += name.utf8.count + value.utf8.count
            guard headerBytes <= 32768, !name.isEmpty,
                  !["host", "content-length", "connection", "transfer-encoding"].contains(name.lowercased()),
                  name.utf8.allSatisfy({ byte in
                      (48...57).contains(byte) || (65...90).contains(byte) ||
                      (97...122).contains(byte) || "!#$%&'*+-.^_`|~".utf8.contains(byte)
                  }), !value.contains("\r"), !value.contains("\n"), !value.contains("\0") else {
                throw HTTPFailure.invalid
            }

            outgoing.setValue(value, forHTTPHeaderField: name)
        }

        request = outgoing
        pins = try CertificatePins(values)
        self.completion = completion
        queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        super.init()
    }

    func start() {
        queue.addOperation {
            guard !self.terminal else { return }

            let configuration = URLSessionConfiguration.ephemeral

            configuration.httpCookieStorage = nil
            configuration.urlCache = nil
            configuration.urlCredentialStorage = nil
            configuration.timeoutIntervalForRequest = 30
            configuration.timeoutIntervalForResource = 30
            self.session = URLSession(configuration: configuration, delegate: self, delegateQueue: self.queue)
            self.session?.dataTask(with: self.request).resume()
        }
    }

    func cancel() {
        queue.addOperation { self.finish(code: 90) }
    }

    private func finish(code: Int? = nil) {
        guard !terminal else { return }

        terminal = true
        if let code {
            completion(nil, nil, code)
        } else if let response {
            let headers = response.allHeaderFields.reduce(into: [String: String]()) { output, pair in
                if let name = pair.key as? String { output[name] = String(describing: pair.value) }
            }

            completion(["status": response.statusCode, "url": request.url!.absoluteString,
                        "headers": headers], data, nil)
        } else {
            completion(nil, nil, -1)
        }

        data.resetBytes(in: 0..<data.count)
        data.removeAll(keepingCapacity: false)
        session?.invalidateAndCancel()
        session = nil
    }

    func urlSession(_ session: URLSession,
                    didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        authenticate(challenge, completionHandler: completionHandler)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        authenticate(challenge, completionHandler: completionHandler)
    }

    private func authenticate(_ challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard !terminal else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust {
            guard let trust = challenge.protectionSpace.serverTrust,
                  challenge.protectionSpace.host.lowercased() == request.url?.host?.lowercased(),
                  pins.accepts(trust, host: challenge.protectionSpace.host) else {
                failureCode = 88
                completionHandler(.cancelAuthenticationChallenge, nil)
                return
            }

            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        // Return the original 3xx. Never send authorization/body to another URL.
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard !terminal, let response = response as? HTTPURLResponse else {
            completionHandler(.cancel)
            return
        }

        let headerBytes = response.allHeaderFields.reduce(0) {
            $0 + String(describing: $1.key).utf8.count + String(describing: $1.value).utf8.count
        }

        guard response.expectedContentLength <= Int64(Self.maxBytes), headerBytes <= 32768 else {
            completionHandler(.cancel)
            finish(code: 91)
            return
        }

        self.response = response
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard !terminal else { return }
        guard data.count <= Self.maxBytes - self.data.count else {
            finish(code: 91)
            return
        }

        self.data.append(data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = error as NSError? {
            let tls = [NSURLErrorSecureConnectionFailed, NSURLErrorServerCertificateHasBadDate,
                       NSURLErrorServerCertificateUntrusted, NSURLErrorServerCertificateHasUnknownRoot,
                       NSURLErrorServerCertificateNotYetValid].contains(error.code)

            finish(code: failureCode ?? (tls ? 88 : -1))
        } else {
            finish()
        }
    }
}
