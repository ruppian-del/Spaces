import CryptoKit
import Foundation
import Security

struct EncryptedMessagePayload {
    let ciphertext: String
    let nonce: String
    let tag: String
}

final class EncryptionService {
    private let keychainService = "com.ianrupp.spaces.e2ee"
    private var cachedSpaceKeys: [String: SymmetricKey] = [:]

    func ensurePublicKey(for userID: String) throws -> String {
        let privateKey = try loadPrivateKey(for: userID) ?? generateAndStorePrivateKey(for: userID)
        return privateKey.publicKey.derRepresentation.base64EncodedString()
    }

    func cachedSpaceKey(for spaceID: String) -> SymmetricKey? {
        cachedSpaceKeys[spaceID]
    }

    func cacheSpaceKey(_ key: SymmetricKey, for spaceID: String) {
        cachedSpaceKeys[spaceID] = key
    }

    func generateSpaceKey() -> SymmetricKey {
        SymmetricKey(size: .bits256)
    }

    func generateSpaceKeyBase64() -> String {
        Self.symmetricKeyData(generateSpaceKey()).base64EncodedString()
    }

    func encodeSpaceKey(_ key: SymmetricKey) -> String {
        Self.symmetricKeyData(key).base64EncodedString()
    }

    func decodeSpaceKey(_ encoded: String) throws -> SymmetricKey {
        SymmetricKey(data: try Self.decodeBase64(encoded))
    }

    func wrapSpaceKey(
        _ spaceKey: SymmetricKey,
        for recipientPublicKey: String,
        senderUserID: String
    ) throws -> String {
        let senderPrivateKey = try requirePrivateKey(for: senderUserID)
        let recipientKey = try Self.decodePublicKey(recipientPublicKey)
        let sharedSecret = try senderPrivateKey.sharedSecretFromKeyAgreement(with: recipientKey)
        let wrappingKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: Data("spaces-wrap-v1".utf8),
            outputByteCount: 32
        )
        let sealedBox = try AES.GCM.seal(Self.symmetricKeyData(spaceKey), using: wrappingKey)
        guard let combined = sealedBox.combined else {
            throw EncryptionServiceError.unableToWrapSpaceKey
        }
        return combined.base64EncodedString()
    }

    func unwrapSpaceKey(
        _ wrappedKey: String,
        wrappedBy senderPublicKey: String,
        recipientUserID: String
    ) throws -> SymmetricKey {
        let recipientPrivateKey = try requirePrivateKey(for: recipientUserID)
        let senderKey = try Self.decodePublicKey(senderPublicKey)
        let sharedSecret = try recipientPrivateKey.sharedSecretFromKeyAgreement(with: senderKey)
        let wrappingKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: Data("spaces-wrap-v1".utf8),
            outputByteCount: 32
        )
        let sealedBox: AES.GCM.SealedBox
        if wrappedKey.contains(".") {
            let parts = wrappedKey.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
            guard parts.count == 3 else {
                throw EncryptionServiceError.invalidEncodedPayload
            }

            let nonceData = try Self.decodeBase64(parts[0])
            let ciphertextData = try Self.decodeBase64(parts[1])
            let tagData = try Self.decodeBase64(parts[2])
            sealedBox = try AES.GCM.SealedBox(
                nonce: try AES.GCM.Nonce(data: nonceData),
                ciphertext: ciphertextData,
                tag: tagData
            )
        } else {
            let wrappedData = try Self.decodeBase64(wrappedKey)
            sealedBox = try AES.GCM.SealedBox(combined: wrappedData)
        }
        let keyData = try AES.GCM.open(sealedBox, using: wrappingKey)
        return SymmetricKey(data: keyData)
    }

    func encryptText(_ text: String, using spaceKey: SymmetricKey) throws -> EncryptedMessagePayload {
        let sealedBox = try AES.GCM.seal(Data(text.utf8), using: spaceKey)
        let ciphertextWithTag = sealedBox.ciphertext + sealedBox.tag
        return EncryptedMessagePayload(
            ciphertext: ciphertextWithTag.base64EncodedString(),
            nonce: sealedBox.nonce.withUnsafeBytes { Data($0).base64EncodedString() },
            tag: ""
        )
    }

    func decryptText(
        ciphertext: String,
        nonce: String,
        tag: String? = nil,
        using spaceKey: SymmetricKey
    ) throws -> String {
        let ciphertextData = try Self.decodeBase64(ciphertext)
        let nonceData = try Self.decodeBase64(nonce)
        let sealedBox: AES.GCM.SealedBox
        if let tag, !tag.isEmpty {
            let tagData = try Self.decodeBase64(tag)
            sealedBox = try AES.GCM.SealedBox(
                nonce: try AES.GCM.Nonce(data: nonceData),
                ciphertext: ciphertextData,
                tag: tagData
            )
        } else {
            sealedBox = try AES.GCM.SealedBox(combined: nonceData + ciphertextData)
        }
        let data = try AES.GCM.open(sealedBox, using: spaceKey)
        guard let text = String(data: data, encoding: .utf8) else {
            throw EncryptionServiceError.unableToDecryptMessage
        }
        return text
    }

    func encryptData(_ data: Data, using spaceKey: SymmetricKey) throws -> EncryptedMessagePayload {
        let sealedBox = try AES.GCM.seal(data, using: spaceKey)
        let ciphertextWithTag = sealedBox.ciphertext + sealedBox.tag
        return EncryptedMessagePayload(
            ciphertext: ciphertextWithTag.base64EncodedString(),
            nonce: sealedBox.nonce.withUnsafeBytes { Data($0).base64EncodedString() },
            tag: ""
        )
    }

    func decryptData(
        ciphertext: String,
        nonce: String,
        using spaceKey: SymmetricKey
    ) throws -> Data {
        let ciphertextData = try Self.decodeBase64(ciphertext)
        let nonceData = try Self.decodeBase64(nonce)
        let sealedBox = try AES.GCM.SealedBox(combined: nonceData + ciphertextData)
        return try AES.GCM.open(sealedBox, using: spaceKey)
    }

    private func requirePrivateKey(for userID: String) throws -> P256.KeyAgreement.PrivateKey {
        guard let privateKey = try loadPrivateKey(for: userID) else {
            throw EncryptionServiceError.identityKeyMissing
        }
        return privateKey
    }

    private func generateAndStorePrivateKey(for userID: String) throws -> P256.KeyAgreement.PrivateKey {
        let privateKey = P256.KeyAgreement.PrivateKey()
        try storePrivateKey(privateKey, for: userID)
        return privateKey
    }

    private func loadPrivateKey(for userID: String) throws -> P256.KeyAgreement.PrivateKey? {
        var query = keychainQuery(for: userID)
        query[kSecReturnData as String] = kCFBooleanTrue
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data else {
                throw EncryptionServiceError.identityKeyMissing
            }
            return try P256.KeyAgreement.PrivateKey(rawRepresentation: data)
        case errSecItemNotFound:
            return nil
        default:
            throw EncryptionServiceError.keychainFailure(status)
        }
    }

    private func storePrivateKey(_ privateKey: P256.KeyAgreement.PrivateKey, for userID: String) throws {
        let data = privateKey.rawRepresentation
        var query = keychainQuery(for: userID)
        SecItemDelete(query as CFDictionary)
        query[kSecValueData as String] = data

        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw EncryptionServiceError.keychainFailure(status)
        }
    }

    private func keychainQuery(for userID: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: "identity-\(userID)",
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
    }

    private static func symmetricKeyData(_ key: SymmetricKey) -> Data {
        key.withUnsafeBytes { Data($0) }
    }

    private static func decodeBase64(_ value: String) throws -> Data {
        guard let data = Data(base64Encoded: value) else {
            throw EncryptionServiceError.invalidEncodedPayload
        }
        return data
    }

    private static func decodePublicKey(_ encoded: String) throws -> P256.KeyAgreement.PublicKey {
        let data = try decodeBase64(encoded)

        if let key = try? P256.KeyAgreement.PublicKey(derRepresentation: data) {
            return key
        }

        if let key = try? P256.KeyAgreement.PublicKey(rawRepresentation: data) {
            return key
        }

        throw EncryptionServiceError.invalidEncodedPayload
    }
}

enum EncryptionServiceError: LocalizedError {
    case identityKeyMissing
    case invalidEncodedPayload
    case unableToWrapSpaceKey
    case unableToEncryptMessage
    case unableToDecryptMessage
    case keychainFailure(OSStatus)

    var errorDescription: String? {
        switch self {
        case .identityKeyMissing:
            return "This device is missing its encryption identity."
        case .invalidEncodedPayload:
            return "Encrypted data could not be decoded."
        case .unableToWrapSpaceKey:
            return "Unable to prepare the Space encryption key."
        case .unableToEncryptMessage:
            return "Unable to encrypt this message."
        case .unableToDecryptMessage:
            return "Unable to decrypt messages for this Space yet."
        case .keychainFailure:
            return "Secure storage is unavailable on this device."
        }
    }
}
