import Foundation

struct SpaceEvent: Identifiable, Hashable {
    let id: String
    let spaceID: String
    let title: String
    let description: String
    let location: String
    let startDate: Date
    let endDate: Date
    let allDay: Bool
    let timeZoneIdentifier: String
    let createdBy: String
    let createdByName: String
    let createdAt: Date?
    let updatedAt: Date?
    let deleted: Bool
}

extension SpaceEvent {
    var timeZone: TimeZone {
        TimeZone(identifier: timeZoneIdentifier) ?? .current
    }

    var dateText: String {
        if allDay {
            return Self.allDayDateFormatter.string(from: startDate)
        }
        return Self.dateFormatter.string(from: startDate)
    }

    var timeText: String {
        guard !allDay else { return "All Day" }
        let start = Self.timeFormatter.string(from: startDate)
        let end = Self.timeFormatter.string(from: endDate)
        return "\(start) – \(end)"
    }

    var sortDate: Date {
        startDate
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter
    }()

    private static let allDayDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "EEEE, MMM d"
        return formatter
    }()

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter
    }()
}

struct EditableSpaceEvent {
    var title: String
    var description: String
    var location: String
    var startDate: Date
    var endDate: Date
    var allDay: Bool

    init(
        title: String = "",
        description: String = "",
        location: String = "",
        startDate: Date = Date(),
        endDate: Date = Calendar.current.date(byAdding: .hour, value: 1, to: Date()) ?? Date(),
        allDay: Bool = false
    ) {
        self.title = title
        self.description = description
        self.location = location
        self.startDate = startDate
        self.endDate = endDate
        self.allDay = allDay
    }

    init(event: SpaceEvent) {
        self.title = event.title
        self.description = event.description
        self.location = event.location
        self.startDate = event.startDate
        self.endDate = event.endDate
        self.allDay = event.allDay
    }

    var trimmedTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var canSave: Bool {
        !trimmedTitle.isEmpty && endDate >= startDate
    }
}

enum EventEditorMode {
    case create
    case edit(SpaceEvent)

    var navigationTitle: String {
        switch self {
        case .create:
            return "Add Event"
        case .edit:
            return "Edit Event"
        }
    }
}
