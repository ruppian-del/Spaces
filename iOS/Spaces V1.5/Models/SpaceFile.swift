import Foundation

struct SpaceFolder: Identifiable, Hashable {
    let id: String
    let name: String
    let createdBy: String
    let createdAt: Date?

    var timestamp: String {
        guard let createdAt else { return "Recently" }
        return Self.timestampFormatter.localizedString(for: createdAt, relativeTo: Date())
    }

    private static let timestampFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter
    }()
}

struct SpaceFileItem: Identifiable, Hashable {
    let id: String
    let spaceID: String
    let name: String
    let mimeType: String
    let folderId: String?
    let storagePath: String
    let encryptionVersion: String
    let nonceBase64: String
    let uploadedBy: String
    let uploadedByName: String
    let fileExtension: String
    let createdAt: Date?
    let updatedAt: Date?
    let sizeBytes: Int64
    let deleted: Bool

    var timestamp: String {
        guard let createdAt else { return "Recently" }
        return Self.timestampFormatter.localizedString(for: createdAt, relativeTo: Date())
    }

    var sizeDescription: String {
        ByteCountFormatter.string(fromByteCount: sizeBytes, countStyle: .file)
    }

    var iconName: String {
        if mimeType.hasPrefix("image/") {
            return "photo"
        }
        if mimeType.hasPrefix("video/") {
            return "film"
        }
        if mimeType.hasPrefix("audio/") {
            return "waveform"
        }
        if mimeType.contains("pdf") {
            return "doc.richtext"
        }
        if mimeType.contains("zip") || mimeType.contains("compressed") {
            return "archivebox"
        }
        if mimeType.contains("json") || mimeType.contains("csv") || mimeType.hasPrefix("text/") {
            return "doc.text"
        }
        return "doc"
    }

    var typeDescription: String {
        if mimeType.hasPrefix("image/") {
            return "Image"
        }
        if mimeType.hasPrefix("video/") {
            return "Video"
        }
        if mimeType.hasPrefix("audio/") {
            return "Audio"
        }
        if mimeType.contains("pdf") {
            return "PDF"
        }
        if mimeType.contains("spreadsheet") || mimeType.contains("csv") {
            return "Spreadsheet"
        }
        if mimeType.contains("presentation") {
            return "Presentation"
        }
        if mimeType.contains("wordprocessingml") || mimeType.contains("msword") {
            return "Document"
        }
        if mimeType.contains("zip") || mimeType.contains("compressed") {
            return "Archive"
        }
        if mimeType.contains("json") {
            return "JSON"
        }
        if mimeType.hasPrefix("text/") {
            return "Text"
        }
        return "File"
    }

    var isImage: Bool {
        mimeType.hasPrefix("image/")
    }

    var isVideo: Bool {
        mimeType.hasPrefix("video/")
    }

    private static let timestampFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter
    }()
}
