import SwiftUI

struct GeneralPlaceholderView: View {
    let space: Space

    var body: some View {
        GeneralView(space: space)
    }
}

struct PhotosPlaceholderView: View {
    let space: Space

    var body: some View {
        PhotosView(space: space)
    }
}

struct RoomsPlaceholderView: View {
    let space: Space

    var body: some View {
        SpaceModulePlaceholderContainer(
            title: "Rooms",
            emoji: "💬",
            description: "Room conversations are not available in this build yet."
        )
    }
}

struct FilesPlaceholderView: View {
    let space: Space

    var body: some View {
        FilesView(space: space)
    }
}

struct PollsPlaceholderView: View {
    let space: Space

    var body: some View {
        PollsView(space: space)
    }
}

struct EventsPlaceholderView: View {
    let space: Space

    var body: some View {
        EventsView(space: space)
    }
}

struct MembersPlaceholderView: View {
    let space: Space

    var body: some View {
        MembersView(space: space)
    }
}

struct SpaceSettingsPlaceholderView: View {
    let space: Space

    var body: some View {
        SpaceSettingsView(space: space)
    }
}

private struct SpaceModulePlaceholderContainer: View {
    let title: String
    let emoji: String
    let description: String

    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    Text(emoji)
                        .font(.largeTitle)

                    VStack(alignment: .leading, spacing: 6) {
                        Text(title)
                            .font(.headline)
                        Text(description)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 6)
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
