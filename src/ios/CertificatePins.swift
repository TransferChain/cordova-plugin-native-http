import Foundation
import Security
import CryptoKit

// Immutable request policy. Match only certificates from SecTrust's verified chain.
struct CertificatePins {
    struct Pin {
        let spki: Bool
        let hash: Data
    }

    let pins: [Pin]

    init(_ values: [String]) throws {
        guard values.count <= 16 else { throw Failure.invalid }

        pins = try values.map { value in
            let parts = value.split(separator: ":", omittingEmptySubsequences: false)

            guard parts.count == 2, parts[0] == "spki" || parts[0] == "certificate",
                  parts[1].count == 64 else { throw Failure.invalid }

            let chars = Array(parts[1])
            var bytes = Data()

            for index in stride(from: 0, to: 64, by: 2) {
                guard let byte = UInt8(String(chars[index...index + 1]), radix: 16) else {
                    throw Failure.invalid
                }

                bytes.append(byte)
            }

            return Pin(spki: parts[0] == "spki", hash: bytes)
        }
    }

    func accepts(_ trust: SecTrust, host: String) -> Bool {
        // Fingerprints add restrictions; they cannot approve an expired/untrusted
        // chain or a certificate issued for another hostname.
        SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, host as CFString))
        guard SecTrustEvaluateWithError(trust, nil) else { return false }
        if pins.isEmpty { return true }

        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate] else { return false }

        for certificate in chain {
            let der = SecCertificateCopyData(certificate) as Data

            for pin in pins {
                let encoded = pin.spki ? Self.subjectPublicKeyInfo(der) : der

                if let encoded, Self.equal(Data(SHA256.hash(data: encoded)), pin.hash) {
                    return true
                }
            }
        }

        return false
    }

    static func equal(_ left: Data, _ right: Data) -> Bool {
        guard left.count == 32, right.count == 32 else { return false }

        var difference: UInt8 = 0

        for (a, b) in zip(left, right) { difference |= a ^ b }

        return difference == 0
    }

    // SecKey external representations differ by algorithm and omit the SPKI
    // wrapper. Preserve the exact SPKI DER from an already-validated certificate.
    // Every length is bounded by its parent before indexing or slicing.
    static func subjectPublicKeyInfo(_ certificate: Data) -> Data? {
        let bytes = [UInt8](certificate)

        func element(_ offset: Int, _ limit: Int) -> (tag: UInt8, body: Int, end: Int)? {
            guard offset >= 0, limit <= bytes.count, offset + 2 <= limit else { return nil }

            var cursor = offset + 2
            var length = Int(bytes[offset + 1])

            if length >= 128 {
                let count = length & 127

                guard count > 0, count <= 4, cursor + count <= limit else { return nil }

                length = 0
                for _ in 0..<count {
                    length = length * 256 + Int(bytes[cursor])
                    cursor += 1
                }
            }

            guard length <= limit - cursor else { return nil }

            return (bytes[offset], cursor, cursor + length)
        }

        guard let root = element(0, bytes.count), root.tag == 0x30, root.end == bytes.count,
              let tbs = element(root.body, root.end), tbs.tag == 0x30 else { return nil }

        var cursor = tbs.body

        if let version = element(cursor, tbs.end), version.tag == 0xa0 { cursor = version.end }

        // Skip serial, signature algorithm, issuer, validity and subject.
        for _ in 0..<5 {
            guard let field = element(cursor, tbs.end) else { return nil }

            cursor = field.end
        }

        guard let spki = element(cursor, tbs.end), spki.tag == 0x30 else { return nil }

        return Data(bytes[cursor..<spki.end])
    }

    enum Failure: Error { case invalid }
}
