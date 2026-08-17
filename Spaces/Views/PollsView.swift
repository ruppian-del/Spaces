import SwiftUI

struct PollsView: View {
    @StateObject private var viewModel: PollsViewModel
    @State private var isPresentingCreatePoll = false
    @State private var selectedLinkedPollID: String?
    private let initialPollID: String?

    init(space: Space, initialPollID: String? = nil) {
        _viewModel = StateObject(wrappedValue: PollsViewModel(space: space))
        self.initialPollID = initialPollID
    }

    var body: some View {
        List {
            if viewModel.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 24)
                    .listRowBackground(Color.clear)
            } else if viewModel.polls.isEmpty {
                emptyState
                    .listRowInsets(EdgeInsets(top: 20, leading: 20, bottom: 20, trailing: 20))
                    .listRowBackground(Color.clear)
            } else {
                Section("Polls") {
                    ForEach(viewModel.polls) { poll in
                        NavigationLink {
                            PollDetailView(viewModel: viewModel, pollID: poll.id)
                        } label: {
                            PollRowView(
                                poll: poll,
                                tintHex: viewModel.space.tintHex
                            )
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .background(
            NavigationLink(
                destination: linkedPollDestination,
                isActive: Binding(
                    get: { selectedLinkedPollID != nil },
                    set: { if !$0 { selectedLinkedPollID = nil } }
                )
            ) { EmptyView() }
            .hidden()
        )
        .navigationTitle("Polls")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            viewModel.startListeningIfNeeded()
        }
        .onChange(of: viewModel.polls) { polls in
            guard let initialPollID else { return }
            if selectedLinkedPollID == nil, let poll = polls.first(where: { $0.id == initialPollID }) {
                selectedLinkedPollID = poll.id
            }
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    isPresentingCreatePoll = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(isPresented: $isPresentingCreatePoll) {
            NavigationView {
                CreatePollView { question, options, closesAt, allowMultipleVotes, anonymous in
                    let didCreate = await viewModel.createPoll(
                        question: question,
                        optionTexts: options,
                        closesAt: closesAt,
                        allowMultipleVotes: allowMultipleVotes,
                        anonymous: anonymous
                    )
                    if didCreate {
                        isPresentingCreatePoll = false
                    }
                } onCancel: {
                    isPresentingCreatePoll = false
                }
            }
        }
        .alert("Polls", isPresented: Binding(
            get: { viewModel.errorMessage != nil },
            set: { if !$0 { viewModel.errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
    }

    @ViewBuilder
    private var linkedPollDestination: some View {
        if let pollID = selectedLinkedPollID {
            PollDetailView(viewModel: viewModel, pollID: pollID)
        } else {
            EmptyView()
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "chart.bar.xaxis")
                .font(.system(size: 36, weight: .semibold))
                .foregroundStyle(Color(hex: viewModel.space.tintHex))

            Text("No Polls Yet")
                .font(.headline)

            Text("Create polls for \(viewModel.space.name) to gather quick votes, decisions, and check-ins.")
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

private struct PollRowView: View {
    let poll: SpacePoll
    let tintHex: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "chart.bar.xaxis")
                    .foregroundStyle(Color(hex: tintHex))
                    .font(.headline)

                VStack(alignment: .leading, spacing: 6) {
                    Text(poll.question)
                        .font(.headline)

                    Text("\(poll.totalVotes) vote\(poll.totalVotes == 1 ? "" : "s") • \(poll.options.count) options")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                if poll.isClosed {
                    Text("Closed")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
            }

            if let leadingOption = poll.options.first {
                PollResultPreviewBar(
                    title: leadingOption.text,
                    progress: poll.percentage(for: leadingOption.id),
                    tintHex: tintHex
                )
            }
        }
        .padding(.vertical, 4)
    }
}

struct PollDetailView: View {
    @ObservedObject var viewModel: PollsViewModel
    let pollID: String
    @State private var shouldConfirmDelete = false

    private var poll: SpacePoll? {
        viewModel.poll(withID: pollID)
    }

    var body: some View {
        Group {
            if let poll {
                List {
                    Section {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(poll.question)
                                .font(.title2.bold())

                            Text("Created by \(poll.createdByName)")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)

                            if let closesAt = poll.closesAt {
                                Label(
                                    poll.isClosed
                                    ? "Closed \(closesAt.formatted(date: .abbreviated, time: .shortened))"
                                    : "Closes \(closesAt.formatted(date: .abbreviated, time: .shortened))",
                                    systemImage: "calendar"
                                )
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            }

                            if poll.allowMultipleVotes {
                                Label("Multiple votes allowed", systemImage: "checklist")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }

                            if poll.anonymous {
                                Label("Anonymous poll", systemImage: "eye.slash")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .padding(.vertical, 4)
                    }

                    Section("Results") {
                        ForEach(poll.options) { option in
                            PollOptionResultRow(
                                option: option,
                                poll: poll,
                                tintHex: viewModel.space.tintHex,
                                isSelected: poll.selectedOptionIDs(for: viewModel.currentUserID).contains(option.id)
                            ) {
                                Task {
                                    await viewModel.toggleVote(for: option.id, in: poll)
                                }
                            }
                        }
                    }

                    Section {
                        HStack {
                            Text("Total Votes")
                            Spacer()
                            Text("\(poll.totalVotes)")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .listStyle(.insetGrouped)
                .navigationTitle("Poll")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    if viewModel.canDelete(poll) {
                        ToolbarItem(placement: .navigationBarTrailing) {
                            Button(role: .destructive) {
                                shouldConfirmDelete = true
                            } label: {
                                Image(systemName: "trash")
                            }
                        }
                    }
                }
                .alert("Delete this poll?", isPresented: $shouldConfirmDelete) {
                    Button("Cancel", role: .cancel) { }
                    Button("Delete", role: .destructive) {
                        Task {
                            await viewModel.delete(poll)
                        }
                    }
                } message: {
                    Text("This poll will be hidden from the Space, but its Firestore document will remain for now.")
                }
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "chart.bar.xaxis")
                        .font(.system(size: 32, weight: .semibold))
                        .foregroundStyle(.secondary)

                    Text("Poll Unavailable")
                        .font(.headline)

                    Text("This poll may have been deleted or is no longer available.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(24)
            }
        }
    }
}

private struct PollOptionResultRow: View {
    let option: SpacePollOption
    let poll: SpacePoll
    let tintHex: String
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Text(option.text)
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)

                    Spacer()

                    if isSelected {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(Color(hex: tintHex))
                    }

                    Text("\(poll.votesCount(for: option.id))")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)
                }

                PollResultPreviewBar(
                    title: nil,
                    progress: poll.percentage(for: option.id),
                    tintHex: tintHex
                )

                Text("\(Int((poll.percentage(for: option.id) * 100).rounded()))%")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(poll.isClosed)
    }
}

private struct PollResultPreviewBar: View {
    let title: String?
    let progress: Double
    let tintHex: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let title {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            GeometryReader { proxy in
                let width = max(proxy.size.width * progress, progress > 0 ? 8 : 0)

                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Color(.tertiarySystemFill))

                    Capsule()
                        .fill(Color(hex: tintHex))
                        .frame(width: width)
                }
            }
            .frame(height: 10)
        }
    }
}

private struct CreatePollView: View {
    @State private var question = ""
    @State private var optionOne = ""
    @State private var optionTwo = ""
    @State private var optionThree = ""
    @State private var optionFour = ""
    @State private var hasClosingDate = false
    @State private var closesAt = Date().addingTimeInterval(60 * 60 * 24)
    @State private var allowMultipleVotes = false
    @State private var anonymous = false
    @State private var isSubmitting = false

    let onCreate: (_ question: String, _ options: [String], _ closesAt: Date?, _ allowMultipleVotes: Bool, _ anonymous: Bool) async -> Void
    let onCancel: () -> Void

    var body: some View {
        Form {
            Section("Question") {
                TextField("What should we decide?", text: $question)
            }

            Section("Options") {
                TextField("Option 1", text: $optionOne)
                TextField("Option 2", text: $optionTwo)
                TextField("Option 3 (optional)", text: $optionThree)
                TextField("Option 4 (optional)", text: $optionFour)
            }

            Section("Settings") {
                Toggle("Allow Multiple Votes", isOn: $allowMultipleVotes)
                Toggle("Anonymous", isOn: $anonymous)
                Toggle("Close Poll Automatically", isOn: $hasClosingDate)

                if hasClosingDate {
                    DatePicker("Closes At", selection: $closesAt, in: Date()..., displayedComponents: [.date, .hourAndMinute])
                }
            }
        }
        .navigationTitle("Create Poll")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel", action: onCancel)
            }

            ToolbarItem(placement: .confirmationAction) {
                Button(isSubmitting ? "Creating..." : "Create") {
                    isSubmitting = true
                    Task {
                        await onCreate(
                            question,
                            [optionOne, optionTwo, optionThree, optionFour],
                            hasClosingDate ? closesAt : nil,
                            allowMultipleVotes,
                            anonymous
                        )
                        isSubmitting = false
                    }
                }
                .disabled(!canCreate || isSubmitting)
            }
        }
    }

    private var canCreate: Bool {
        let trimmedOptions = [optionOne, optionTwo, optionThree, optionFour]
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return !question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && trimmedOptions.count >= 2
    }
}
