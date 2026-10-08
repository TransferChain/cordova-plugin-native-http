import XCTest
import Cordova
import CryptoTestSupport
import Security
import CryptoKit
@testable import NativeCryptoPlugins

final class HTTPPluginTests: XCTestCase {
    // Execute on macOS/Xcode: real per-job URLSessions, not a mocked network.
    // Callback IDs keep failure responses attributable even without a response URL.
    func testParallelNativeRequestsKeepCallbacksAndPinsIsolated() throws {
        let plugin = UltraHTTP()
        let delegate = CryptoTestDelegate()
        let lock = NSLock()
        var replies: [String: CDVPluginResult] = [:]
        var counts: [String: Int] = [:]
        let completed = expectation(description: "parallel UltraHTTP")
        completed.expectedFulfillmentCount = 8
        completed.assertForOverFulfill = true
        delegate.onResultWithCallbackId = { result, id in
            lock.lock()
            replies[id] = result
            counts[id, default: 0] += 1
            lock.unlock()
            completed.fulfill()
        }
        plugin.commandDelegate = delegate
        defer { plugin.onReset() }
        for index in 0..<8 {
            let id = "parallel-" + String(index)
            let options: [String: Any] = [
                "id": id, "url": "https://example.org/?http-ios-parallel=" + String(index),
                "method": "GET", "headers": [:] as [String: String], "hasBody": false,
                "fingerprints": index % 2 == 0 ? [] : ["certificate:" + String(repeating: "00", count: 32)]
            ]
            plugin.request(CDVInvokedUrlCommand(arguments: [options, Data()], callbackId: id,
                                               className: "UltraHTTP", methodName: "request"))
        }
        wait(for: [completed], timeout: 45)
        lock.lock()
        let results = replies
        let callbackCounts = counts
        lock.unlock()
        XCTAssertEqual(results.count, 8)
        for index in 0..<8 {
            let id = "parallel-" + String(index)
            let result = try XCTUnwrap(results[id])
            XCTAssertEqual(callbackCounts[id], 1)
            if index % 2 == 1 {
                XCTAssertEqual(result.status.intValue, Int(CDVCommandStatus_ERROR.rawValue))
                let error = try XCTUnwrap(result.message as? [String: Any])
                XCTAssertEqual(error["code"] as? Int, 88)
            } else {
                XCTAssertEqual(result.status.intValue, Int(CDVCommandStatus_OK.rawValue))
                let multipart = try XCTUnwrap(result.message as? [String: Any])
                let messages = try XCTUnwrap(multipart["messages"] as? [Any])
                let metadata = try XCTUnwrap(messages.first as? [String: Any])
                XCTAssertEqual(metadata["status"] as? Int, 200)
                XCTAssertEqual(metadata["url"] as? String, "https://example.org/?http-ios-parallel=" + String(index))
            }
        }
    }

    private func certificate(_ name: String) throws -> SecCertificate {
        let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "der"))
        return try XCTUnwrap(SecCertificateCreateWithData(nil, try Data(contentsOf: url) as CFData))
    }

    private func trust(_ anchor: SecCertificate) throws -> SecTrust {
        let leaf = try certificate("leaf")
        var trust: SecTrust?
        XCTAssertEqual(SecTrustCreateWithCertificates(leaf, SecPolicyCreateSSL(true, "localhost" as CFString), &trust), errSecSuccess)
        let result = try XCTUnwrap(trust)
        SecTrustSetAnchorCertificates(result, [anchor] as CFArray)
        SecTrustSetAnchorCertificatesOnly(result, true)
        SecTrustSetNetworkFetchAllowed(result, false)
        return result
    }

    func testNormalTrustAndMultipleCertificateAndSPKIPins() throws {
        let ca = try certificate("ca")
        let leaf = try certificate("leaf")
        let der = SecCertificateCopyData(leaf) as Data
        let spki = try XCTUnwrap(CertificatePins.subjectPublicKeyInfo(der))
        func digest(_ data: Data) -> String {
            SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        }
        let wrong = "certificate:" + String(repeating: "00", count: 32)
        for values in [[], ["certificate:" + digest(der)], ["spki:" + digest(spki)], [wrong, "spki:" + digest(spki)]] {
            XCTAssertTrue(try CertificatePins(values).accepts(trust(ca), host: "localhost"))
        }
        XCTAssertFalse(try CertificatePins([wrong]).accepts(trust(ca), host: "localhost"))
        XCTAssertFalse(try CertificatePins([]).accepts(trust(ca), host: "wrong.example"))
        XCTAssertFalse(try CertificatePins(["certificate:" + digest(der)]).accepts(trust(certificate("other")), host: "localhost"))
        XCTAssertFalse(try CertificatePins([]).accepts(trust(certificate("other")), host: "localhost"))
    }

    func testMalformedPinsAndDERBounds() throws {
        for value in ["spki:00", "certificate:" + String(repeating: "gg", count: 32), "unknown:" + String(repeating: "00", count: 32)] {
            XCTAssertThrowsError(try CertificatePins([value]))
        }
        XCTAssertThrowsError(try CertificatePins(Array(repeating: "spki:" + String(repeating: "00", count: 32), count: 17)))
        let der = SecCertificateCopyData(try certificate("leaf")) as Data
        for count in 0..<der.count {
            XCTAssertNil(CertificatePins.subjectPublicKeyInfo(Data(der.prefix(count))))
        }
        for malformed in [Data([0x30, 0x80]), Data([0x30, 0x84, 0xff, 0xff, 0xff, 0xff]), Data([0x30, 0x01, 0x30])] {
            XCTAssertNil(CertificatePins.subjectPublicKeyInfo(malformed))
        }
        XCTAssertTrue(CertificatePins.equal(Data(repeating: 1, count: 32), Data(repeating: 1, count: 32)))
        XCTAssertFalse(CertificatePins.equal(Data(repeating: 1, count: 32), Data(repeating: 2, count: 32)))
        XCTAssertFalse(CertificatePins.equal(Data(), Data()))
    }
}
