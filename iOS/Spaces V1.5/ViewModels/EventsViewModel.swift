import Combine
import FirebaseFirestore
import Foundation

@MainActor
final class EventsViewModel: ObservableObject {
    @Published private(set) var events: [SpaceEvent] = []
    @Published private(set) var isLoading = false
    @Published private(set) var selectedEvent: SpaceEvent?
    @Published var editorMode: EventEditorMode?
    @Published var errorMessage: String?
    @Published var isSaving = false
    @Published var isDeleting = false
    @Published private(set) var canCreateEvents = false

    let space: Space

    private let spaceService: SpaceService
    private var eventsListener: ListenerRegistration?

    init(space: Space, spaceService: SpaceService? = nil) {
        self.space = space
        self.spaceService = spaceService ?? SpaceService()
        startListening()
    }

    deinit {
        eventsListener?.remove()
    }

    func startListening() {
        guard eventsListener == nil else { return }
        isLoading = true
        Task {
            canCreateEvents = await spaceService.canPerform(.createEvents, in: space)
        }
        eventsListener = spaceService.listenToEvents(in: space) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let events):
                self.events = events.sorted { $0.sortDate < $1.sortDate }
                self.selectedEvent = self.selectedEvent.flatMap { selected in
                    self.events.first(where: { $0.id == selected.id })
                }
                self.isLoading = false
            case .failure(let error):
                self.errorMessage = error.localizedDescription
                self.isLoading = false
            }
        }
    }

    func presentCreateEvent() {
        guard canCreateEvents else { return }
        editorMode = .create
    }

    func presentEditEvent(_ event: SpaceEvent) {
        editorMode = .edit(event)
    }

    func dismissEditor() {
        editorMode = nil
    }

    func openEvent(_ event: SpaceEvent) {
        selectedEvent = event
    }

    func closeEvent() {
        selectedEvent = nil
    }

    func saveEvent(_ draft: EditableSpaceEvent, editing event: SpaceEvent?) async -> Bool {
        guard !isSaving else { return false }
        isSaving = true
        defer { isSaving = false }

        do {
            if let event {
                try await spaceService.updateEvent(
                    in: space,
                    event: event,
                    title: draft.title,
                    description: draft.description,
                    location: draft.location,
                    startDate: draft.startDate,
                    endDate: draft.endDate,
                    allDay: draft.allDay
                )
            } else {
                _ = try await spaceService.createEvent(
                    in: space,
                    title: draft.title,
                    description: draft.description,
                    location: draft.location,
                    startDate: draft.startDate,
                    endDate: draft.endDate,
                    allDay: draft.allDay
                )
            }
            editorMode = nil
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func deleteEvent(_ event: SpaceEvent) async {
        guard !isDeleting else { return }
        isDeleting = true
        defer { isDeleting = false }

        do {
            try await spaceService.deleteEvent(in: space, event: event)
            if selectedEvent?.id == event.id {
                selectedEvent = nil
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func clearError() {
        errorMessage = nil
    }
}
