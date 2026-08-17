import FirebaseAuth
import FirebaseFirestore
import Foundation

struct SpaceNotificationPreference: Equatable {
    var allEnabled = true
    var categories: [String: Bool] = [:]

    func isEnabled(_ category: String) -> Bool {
        allEnabled && (categories[category] ?? true)
    }

    var firestoreValue: [String: Any] {
        ["allEnabled": allEnabled, "categories": categories]
    }
}

enum SpaceNotificationCategory {
    static let announcements = "announcements"
    static let rooms = "rooms"
    static let notes = "notes"
    static let lists = "lists"
    static let events = "events"
    static let polls = "polls"
    static let mediaAndFiles = "mediaAndFiles"
    static let commentsAndReplies = "commentsAndReplies"
    static let mentions = "mentions"
    static let assignments = "assignments"
}

@MainActor
final class SpaceNotificationPreferencesService {
    private let firestore = Firestore.firestore()

    func load() async throws -> [String: SpaceNotificationPreference] {
        guard let uid = Auth.auth().currentUser?.uid else { return [:] }
        let snapshot = try await firestore.collection("users").document(uid).getDocument()
        return Self.decode(snapshot.data()?["spaceNotificationSettings"])
    }

    func save(_ preference: SpaceNotificationPreference, spaceID: String) async throws {
        guard let uid = Auth.auth().currentUser?.uid else { return }
        let reference = firestore.collection("users").document(uid)
        let snapshot = try await reference.getDocument()
        var settings = snapshot.data()?["spaceNotificationSettings"] as? [String: Any] ?? [:]
        settings[spaceID] = preference.firestoreValue
        try await reference.setData([
            "spaceNotificationSettings": settings,
            "updatedAt": FieldValue.serverTimestamp()
        ], merge: true)
    }

    nonisolated static func decode(_ rawValue: Any?) -> [String: SpaceNotificationPreference] {
        guard let rawSettings = rawValue as? [String: Any] else { return [:] }
        return rawSettings.reduce(into: [:]) { result, entry in
            if let legacyEnabled = entry.value as? Bool {
                result[entry.key] = SpaceNotificationPreference(allEnabled: legacyEnabled)
            } else if let value = entry.value as? [String: Any] {
                let allEnabled = value["allEnabled"] as? Bool ?? true
                let categories = value["categories"] as? [String: Bool] ?? [:]
                result[entry.key] = SpaceNotificationPreference(allEnabled: allEnabled, categories: categories)
            }
        }
    }
}
