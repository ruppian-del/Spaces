import CryptoKit
import FirebaseAuth
import FirebaseFirestore
import FirebaseStorage
import Foundation

@MainActor
final class NoteService {
    private let firestore = Firestore.firestore()
    private let storage = Storage.storage()
    private let encryption = EncryptionService()
    private let spaces = SpaceService()

    func listen(in space: Space, onUpdate: @escaping (Result<[SpaceNote], Error>) -> Void) -> ListenerRegistration {
        firestore.collection("spaces").document(space.id).collection("notes")
            .order(by: "updatedAt", descending: true).addSnapshotListener { [weak self] snapshot, error in
                if let error { onUpdate(.failure(error)); return }
                Task { @MainActor in
                    do {
                        let docs = snapshot?.documents ?? []
                        guard !docs.isEmpty else { onUpdate(.success([])); return }
                        guard let self else { return }
                        let key = try await self.key(space.id)
                        onUpdate(.success(try docs.map { try self.mapNote($0, spaceID: space.id, key: key) }))
                    } catch { onUpdate(.failure(error)) }
                }
            }
    }

    func listenToComments(space: Space, noteID: String, onUpdate: @escaping (Result<[SpaceNoteComment], Error>) -> Void) -> ListenerRegistration {
        firestore.collection("spaces").document(space.id).collection("notes").document(noteID).collection("comments")
            .order(by: "createdAt").addSnapshotListener { [weak self] snapshot, error in
                if let error { onUpdate(.failure(error)); return }
                Task { @MainActor in
                    do {
                        let docs = snapshot?.documents ?? []
                        guard !docs.isEmpty else { onUpdate(.success([])); return }
                        guard let self else { return }
                        let key = try await self.key(space.id)
                        let comments = try docs.map { doc -> SpaceNoteComment in
                            let data = doc.data()
                            let decrypted = try self.encryption.decryptData(ciphertext: data["ciphertext"] as? String ?? "", nonce: data["nonce"] as? String ?? "", using: key)
                            let payload = try JSONDecoder().decode(SpaceNoteCommentPayload.self, from: decrypted)
                            return .init(id: doc.documentID, noteID: noteID, authorID: data["authorId"] as? String ?? "", authorName: payload.authorName, body: payload.body, createdAt: (data["createdAt"] as? Timestamp)?.dateValue() ?? Date())
                        }
                        onUpdate(.success(comments))
                    } catch { onUpdate(.failure(error)) }
                }
            }
    }

    func save(_ note: SpaceNote, in space: Space) async throws {
        guard let uid = Auth.auth().currentUser?.uid else { throw NoteError.notSignedIn }
        let isNew = note.createdBy.isEmpty
        let permission: SpacePermission = isNew ? .createNotes : (note.createdBy == uid ? .editOwnNotes : .editAnyNotes)
        guard await spaces.canPerform(permission, in: space) else { throw NoteError.permissionDenied }
        let encrypted = try encryption.encryptData(JSONEncoder().encode(SpaceNotePayload(title: note.title, markdown: note.markdown, attachments: note.attachments, links: note.links)), using: try await key(space.id))
        try await firestore.collection("spaces").document(space.id).collection("notes").document(note.id).setData([
            "ciphertext": encrypted.ciphertext, "nonce": encrypted.nonce,
            "createdBy": isNew ? uid : note.createdBy,
            "createdAt": isNew ? FieldValue.serverTimestamp() : Timestamp(date: note.createdAt),
            "updatedAt": FieldValue.serverTimestamp(), "encryptionVersion": "space-module-key-v1"
        ], merge: true)
        if isNew {
            await spaces.recordModuleActivity(
                type: .noteCreated,
                in: space,
                title: "created a Note",
                subtitle: note.title,
                targetID: note.id,
                targetType: .notes
            )
        }
        if !isNew {
            let mentionedIDs = await spaces.mentionedMemberIDs(in: note.markdown, space: space)
            await spaces.recordTargetedModuleNotification(
                recipientIDs: mentionedIDs,
                type: .note,
                in: space,
                title: "mentioned you in a Note",
                subtitle: note.title,
                targetID: note.id,
                targetType: .notes
            )
        }
    }

    func delete(_ note: SpaceNote, in space: Space) async throws {
        guard let uid = Auth.auth().currentUser?.uid else { throw NoteError.notSignedIn }
        guard await spaces.canPerform(note.createdBy == uid ? .deleteOwnNotes : .deleteAnyNotes, in: space) else { throw NoteError.permissionDenied }
        let ref = firestore.collection("spaces").document(space.id).collection("notes").document(note.id)
        for attachment in note.attachments { try? await storage.reference(withPath: attachment.storagePath).delete() }
        let comments = try await ref.collection("comments").getDocuments()
        for comment in comments.documents { try await comment.reference.delete() }
        try await ref.delete()
    }

    func addComment(_ body: String, authorName: String, note: SpaceNote, space: Space) async throws {
        guard let uid = Auth.auth().currentUser?.uid else { throw NoteError.notSignedIn }
        let encrypted = try encryption.encryptData(JSONEncoder().encode(SpaceNoteCommentPayload(authorName: authorName, body: body)), using: try await key(space.id))
        try await firestore.collection("spaces").document(space.id).collection("notes").document(note.id).collection("comments").document().setData([
            "ciphertext": encrypted.ciphertext, "nonce": encrypted.nonce, "authorId": uid,
            "createdAt": FieldValue.serverTimestamp(), "encryptionVersion": "space-module-key-v1"
        ])
        let mentionedIDs = await spaces.mentionedMemberIDs(in: body, space: space)
        await spaces.recordTargetedModuleNotification(
            recipientIDs: Array(Set(mentionedIDs + [note.createdBy])),
            type: .note,
            in: space,
            title: "commented on your Note",
            subtitle: note.title,
            targetID: note.id,
            targetType: .notes
        )
    }

    func recordView(noteID: String, spaceID: String) async {
        guard let uid = Auth.auth().currentUser?.uid else { return }
        try? await firestore.collection("spaces").document(spaceID).collection("notePreferences").document(uid).collection("notes").document(noteID).setData([
            "lastViewedAt": FieldValue.serverTimestamp(), "viewCount": FieldValue.increment(Int64(1))
        ], merge: true)
    }

    func listenToViewPreferences(
        spaceID: String,
        onUpdate: @escaping ([String: NoteViewPreference]) -> Void
    ) -> ListenerRegistration? {
        guard let uid = Auth.auth().currentUser?.uid else { return nil }
        return firestore.collection("spaces").document(spaceID)
            .collection("notePreferences").document(uid).collection("notes")
            .addSnapshotListener { snapshot, _ in
                let pairs: [(String, NoteViewPreference)] = (snapshot?.documents ?? []).compactMap { document -> (String, NoteViewPreference)? in
                    guard document.documentID != "_organization" else { return nil }
                    return (
                        document.documentID,
                        NoteViewPreference(
                            lastViewedAt: (document.data()["lastViewedAt"] as? Timestamp)?.dateValue() ?? .distantPast,
                            viewCount: document.data()["viewCount"] as? Int ?? 0
                        )
                    )
                }
                let values: [String: NoteViewPreference] = Dictionary(uniqueKeysWithValues: pairs)
                onUpdate(values)
            }
    }

    func listenToManualOrder(spaceID: String, onUpdate: @escaping ([String]) -> Void) -> ListenerRegistration? {
        guard let uid = Auth.auth().currentUser?.uid else { return nil }
        return firestore.collection("spaces").document(spaceID)
            .collection("notePreferences").document(uid)
            .addSnapshotListener { snapshot, _ in
                onUpdate(snapshot?.data()?["manualOrder"] as? [String] ?? [])
            }
    }

    func saveManualOrder(_ ids: [String], spaceID: String) async {
        guard let uid = Auth.auth().currentUser?.uid else { return }
        try? await firestore.collection("spaces").document(spaceID)
            .collection("notePreferences").document(uid)
            .setData(["manualOrder": ids, "updatedAt": FieldValue.serverTimestamp()], merge: true)
    }

    func upload(data: Data, name: String, mimeType: String, isMedia: Bool, noteID: String, space: Space) async throws -> SpaceNoteAttachment {
        let id = UUID().uuidString
        let encrypted = try encryption.encryptData(data, using: try await key(space.id))
        let path = "spaces/\(space.id)/notes/\(noteID)/attachments/\(id).enc"
        try await storage.reference(withPath: path).putDataAsync(Data(base64Encoded: encrypted.ciphertext) ?? Data())
        return .init(id: id, name: name, mimeType: mimeType, storagePath: path, nonce: encrypted.nonce, isMedia: isMedia)
    }

    func download(_ attachment: SpaceNoteAttachment, space: Space) async throws -> Data {
        let data = try await storage.reference(withPath: attachment.storagePath).data(maxSize: 250 * 1024 * 1024)
        return try encryption.decryptData(ciphertext: data.base64EncodedString(), nonce: attachment.nonce, using: try await key(space.id))
    }

    private func key(_ spaceID: String) async throws -> SymmetricKey { try await spaces.encryptionKeyForModuleData(spaceID: spaceID) }
    private func mapNote(_ doc: DocumentSnapshot, spaceID: String, key: SymmetricKey) throws -> SpaceNote {
        let data = doc.data() ?? [:]
        let decrypted = try encryption.decryptData(ciphertext: data["ciphertext"] as? String ?? "", nonce: data["nonce"] as? String ?? "", using: key)
        let payload = try JSONDecoder().decode(SpaceNotePayload.self, from: decrypted)
        return .init(id: doc.documentID, spaceID: spaceID, title: payload.title, markdown: payload.markdown, attachments: payload.attachments, links: payload.links, createdBy: data["createdBy"] as? String ?? "", createdAt: (data["createdAt"] as? Timestamp)?.dateValue() ?? Date(), updatedAt: (data["updatedAt"] as? Timestamp)?.dateValue() ?? Date())
    }
}

struct NoteViewPreference {
    let lastViewedAt: Date
    let viewCount: Int
}

enum NoteError: LocalizedError {
    case notSignedIn, permissionDenied
    var errorDescription: String? { self == .notSignedIn ? "Sign in to use Notes." : "You do not have permission to change this Note." }
}
