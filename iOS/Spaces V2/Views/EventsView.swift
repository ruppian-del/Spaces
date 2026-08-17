import SwiftUI

struct EventsView: View {
    @StateObject private var viewModel: EventsViewModel

    init(space: Space) {
        _viewModel = StateObject(wrappedValue: EventsViewModel(space: space))
    }

    var body: some View {
        List {
            if viewModel.isLoading && viewModel.events.isEmpty {
                ProgressView()
                    .frame(maxWidth: .infinity, alignment: .center)
                    .listRowBackground(Color.clear)
            } else if viewModel.events.isEmpty {
                emptyState
                    .listRowInsets(EdgeInsets(top: 20, leading: 20, bottom: 20, trailing: 20))
                    .listRowBackground(Color.clear)
            } else {
                ForEach(viewModel.events) { event in
                    NavigationLink {
                        EventDetailView(
                            space: viewModel.space,
                            event: event,
                            onEdit: { viewModel.presentEditEvent(event) },
                            onDelete: {
                                Task {
                                    await viewModel.deleteEvent(event)
                                }
                            }
                        )
                    } label: {
                        EventRowView(event: event, tintHex: viewModel.space.tintHex)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Events")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    viewModel.presentCreateEvent()
                } label: {
                    Image(systemName: "plus")
                }
                .disabled(!viewModel.canCreateEvents)
            }
        }
        .sheet(isPresented: Binding(
            get: { viewModel.editorMode != nil },
            set: { if !$0 { viewModel.dismissEditor() } }
        )) {
            if let editorMode = viewModel.editorMode {
                EventEditorSheet(
                    mode: editorMode,
                    isSaving: viewModel.isSaving,
                    onCancel: { viewModel.dismissEditor() },
                    onSave: { draft, event in
                        await viewModel.saveEvent(draft, editing: event)
                    }
                )
            }
        }
        .alert("Events", isPresented: Binding(
            get: { viewModel.errorMessage != nil },
            set: { if !$0 { viewModel.clearError() } }
        )) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "calendar")
                .font(.system(size: 36, weight: .semibold))
                .foregroundStyle(Color(hex: viewModel.space.tintHex))

            Text("No events yet")
                .font(.headline)

            Text("Create the first event for \(viewModel.space.name).")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(28)
        .background(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(Color(.secondarySystemBackground))
        )
    }
}

private struct EventRowView: View {
    let event: SpaceEvent
    let tintHex: String

    var body: some View {
        HStack(spacing: 14) {
            VStack(spacing: 4) {
                Image(systemName: "calendar")
                    .font(.headline)
                Text(event.allDay ? "All Day" : dayText)
                    .font(.caption2.weight(.semibold))
                    .multilineTextAlignment(.center)
            }
            .foregroundStyle(Color(hex: tintHex))
            .frame(width: 56, height: 60)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color(hex: tintHex).opacity(0.12))
            )

            VStack(alignment: .leading, spacing: 6) {
                Text(event.title)
                    .font(.headline)

                Text(event.dateText)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                Text(event.timeText)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                if !event.location.isEmpty {
                    Label(event.location, systemImage: "mappin.and.ellipse")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var dayText: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "EEE"
        return formatter.string(from: event.startDate).uppercased()
    }
}

struct EventDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var viewModel: EventDetailViewModel
    @State private var showDeleteConfirmation = false

    private let onEdit: () -> Void
    private let onDelete: () -> Void

    init(
        space: Space,
        event: SpaceEvent,
        onEdit: @escaping () -> Void,
        onDelete: @escaping () -> Void
    ) {
        _viewModel = StateObject(wrappedValue: EventDetailViewModel(space: space, event: event))
        self.onEdit = onEdit
        self.onDelete = onDelete
    }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 12) {
                    Text(viewModel.event.title)
                        .font(.title2.bold())

                    Label(viewModel.event.dateText, systemImage: "calendar")
                        .foregroundStyle(.secondary)

                    Label(viewModel.event.timeText, systemImage: "clock")
                        .foregroundStyle(.secondary)

                    if !viewModel.event.location.isEmpty {
                        Label(viewModel.event.location, systemImage: "mappin.and.ellipse")
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
            }

            Section("Details") {
                if !viewModel.event.description.isEmpty {
                    Text(viewModel.event.description)
                        .font(.body)
                }

                Label("Created by \(viewModel.event.createdByName)", systemImage: "person.crop.circle")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Section {
                Button {
                    Task {
                        await viewModel.addToCalendar()
                    }
                } label: {
                    Label(
                        viewModel.isAddingToCalendar ? "Adding to Calendar..." : "Add to Calendar",
                        systemImage: "calendar.badge.plus"
                    )
                }
                .disabled(viewModel.isAddingToCalendar)
            }

            if viewModel.canManage {
                Section {
                    Button("Edit Event") {
                        onEdit()
                    }

                    Button("Delete Event", role: .destructive) {
                        showDeleteConfirmation = true
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Event")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await viewModel.loadPermissions()
        }
        .confirmationDialog("Delete this event?", isPresented: $showDeleteConfirmation, titleVisibility: .visible) {
            Button("Delete Event", role: .destructive) {
                onDelete()
                dismiss()
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("This event will be removed from the Space.")
        }
        .alert("Calendar", isPresented: Binding(
            get: { viewModel.calendarResultMessage != nil },
            set: { if !$0 { viewModel.clearCalendarResult() } }
        )) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(viewModel.calendarResultMessage ?? "")
        }
    }
}

private struct EventEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: EditableSpaceEvent

    let mode: EventEditorMode
    let isSaving: Bool
    let onCancel: () -> Void
    let onSave: (EditableSpaceEvent, SpaceEvent?) async -> Bool

    init(
        mode: EventEditorMode,
        isSaving: Bool,
        onCancel: @escaping () -> Void,
        onSave: @escaping (EditableSpaceEvent, SpaceEvent?) async -> Bool
    ) {
        self.mode = mode
        self.isSaving = isSaving
        self.onCancel = onCancel
        self.onSave = onSave

        switch mode {
        case .create:
            _draft = State(initialValue: EditableSpaceEvent())
        case .edit(let event):
            _draft = State(initialValue: EditableSpaceEvent(event: event))
        }
    }

    var body: some View {
        NavigationView {
            Form {
                Section("Event") {
                    TextField("Title", text: $draft.title)
                    TextField("Location", text: $draft.location)
                    Toggle("All-day", isOn: $draft.allDay)
                }

                Section("Schedule") {
                    DatePicker("Date", selection: eventDateBinding, displayedComponents: .date)
                    if draft.allDay {
                        DatePicker("Ends", selection: allDayEndDateBinding, displayedComponents: .date)
                    } else {
                        DatePicker("Start time", selection: $draft.startDate, displayedComponents: .hourAndMinute)
                        DatePicker("End time", selection: $draft.endDate, displayedComponents: .hourAndMinute)
                    }
                }

                Section("Description") {
                    TextEditor(text: $draft.description)
                        .frame(minHeight: 140)
                }
            }
            .navigationTitle(mode.navigationTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        onCancel()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isSaving ? "Saving..." : "Save") {
                        Task {
                            let didSave = await onSave(draft, editingEvent)
                            if didSave {
                                dismiss()
                            }
                        }
                    }
                    .disabled(!draft.canSave || isSaving)
                }
            }
        }
    }

    private var editingEvent: SpaceEvent? {
        if case .edit(let event) = mode {
            return event
        }
        return nil
    }

    private var eventDateBinding: Binding<Date> {
        Binding(
            get: { draft.startDate },
            set: { newDate in
                let calendar = Calendar.current
                let startTime = calendar.dateComponents([.hour, .minute, .second], from: draft.startDate)
                let endTime = calendar.dateComponents([.hour, .minute, .second], from: draft.endDate)
                draft.startDate = calendar.date(bySettingHour: startTime.hour ?? 0, minute: startTime.minute ?? 0, second: startTime.second ?? 0, of: newDate) ?? newDate
                draft.endDate = calendar.date(bySettingHour: endTime.hour ?? 0, minute: endTime.minute ?? 0, second: endTime.second ?? 0, of: newDate) ?? newDate
                if draft.endDate < draft.startDate {
                    draft.endDate = calendar.date(byAdding: .hour, value: 1, to: draft.startDate) ?? draft.startDate
                }
            }
        )
    }

    private var allDayEndDateBinding: Binding<Date> {
        Binding(
            get: { draft.endDate },
            set: { newDate in
                let calendar = Calendar.current
                let endOfDay = calendar.date(bySettingHour: 23, minute: 59, second: 0, of: newDate) ?? newDate
                draft.endDate = max(endOfDay, draft.startDate)
            }
        )
    }
}
