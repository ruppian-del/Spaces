import Combine
import Foundation

@MainActor
final class EventDetailViewModel: ObservableObject {
    let event: SpaceEvent

    @Published private(set) var canManage = false
    @Published var isAddingToCalendar = false
    @Published var calendarResultMessage: String?

    private let space: Space
    private let spaceService: SpaceService
    private let eventCalendarService: EventCalendarService

    init(
        space: Space,
        event: SpaceEvent,
        spaceService: SpaceService? = nil,
        eventCalendarService: EventCalendarService? = nil
    ) {
        self.space = space
        self.event = event
        self.spaceService = spaceService ?? SpaceService()
        self.eventCalendarService = eventCalendarService ?? EventCalendarService()
    }

    func loadPermissions() async {
        canManage = await spaceService.canManageEvent(event, in: space)
    }

    func addToCalendar() async {
        guard !isAddingToCalendar else { return }
        isAddingToCalendar = true
        defer { isAddingToCalendar = false }

        do {
            try await eventCalendarService.addToCalendar(event: event)
            calendarResultMessage = "Event added to your calendar."
        } catch {
            calendarResultMessage = error.localizedDescription
        }
    }

    func clearCalendarResult() {
        calendarResultMessage = nil
    }
}
