import EventKit
import Foundation

struct DeviceCalendarEvent {
    let title: String
    let startDate: Date
    let endDate: Date
    let allDay: Bool
    let location: String
    let notes: String
}

enum EventCalendarServiceError: LocalizedError {
    case permissionDenied
    case restricted
    case noWritableCalendar

    var errorDescription: String? {
        switch self {
        case .permissionDenied:
            return "Calendar access was denied."
        case .restricted:
            return "Calendar access is restricted on this device."
        case .noWritableCalendar:
            return "No writable calendar is available on this device."
        }
    }
}

actor EventCalendarService {
    private let eventStore = EKEventStore()

    func addToCalendar(event: SpaceEvent) async throws {
        let resolvedEvent = try Self.resolve(event: event)
        try await requestAccessIfNeeded()

        guard let calendar = eventStore.defaultCalendarForNewEvents else {
            throw EventCalendarServiceError.noWritableCalendar
        }

        let nativeEvent = EKEvent(eventStore: eventStore)
        nativeEvent.title = resolvedEvent.title
        nativeEvent.startDate = resolvedEvent.startDate
        nativeEvent.endDate = resolvedEvent.endDate
        nativeEvent.isAllDay = resolvedEvent.allDay
        nativeEvent.location = resolvedEvent.location
        nativeEvent.notes = resolvedEvent.notes
        nativeEvent.calendar = calendar
        nativeEvent.alarms = [EKAlarm(relativeOffset: -15 * 60)]

        try eventStore.save(nativeEvent, span: .thisEvent)
    }

    private func requestAccessIfNeeded() async throws {
        if #available(iOS 17.0, *) {
            switch EKEventStore.authorizationStatus(for: .event) {
            case .fullAccess, .writeOnly:
                return
            case .notDetermined:
                let granted = try await requestFullAccessToEvents()
                guard granted else {
                    throw EventCalendarServiceError.permissionDenied
                }
            case .denied:
                throw EventCalendarServiceError.permissionDenied
            case .restricted:
                throw EventCalendarServiceError.restricted
            @unknown default:
                throw EventCalendarServiceError.permissionDenied
            }
        } else {
            switch EKEventStore.authorizationStatus(for: .event) {
            case .authorized:
                return
            case .notDetermined:
                let granted = try await requestLegacyEventAccess()
                guard granted else {
                    throw EventCalendarServiceError.permissionDenied
                }
            case .denied:
                throw EventCalendarServiceError.permissionDenied
            case .restricted:
                throw EventCalendarServiceError.restricted
            @unknown default:
                throw EventCalendarServiceError.permissionDenied
            }
        }
    }

    @available(iOS 17.0, *)
    private func requestFullAccessToEvents() async throws -> Bool {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Bool, Error>) in
            eventStore.requestFullAccessToEvents { granted, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: granted)
                }
            }
        }
    }

    private func requestLegacyEventAccess() async throws -> Bool {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Bool, Error>) in
            eventStore.requestAccess(to: .event) { granted, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: granted)
                }
            }
        }
    }

    private static func resolve(event: SpaceEvent) throws -> DeviceCalendarEvent {
        return DeviceCalendarEvent(
            title: event.title,
            startDate: event.startDate,
            endDate: event.endDate,
            allDay: event.allDay,
            location: event.location,
            notes: "\(event.description)\n\nCreated by \(event.createdByName)"
        )
    }
}
