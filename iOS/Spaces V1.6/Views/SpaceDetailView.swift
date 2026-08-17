import SwiftUI

struct SpaceDetailView: View {
    let space: Space
    @State private var entitledModuleIDs: Set<String>?
    @State private var entitlementError: String?

    private let columns = [GridItem(.flexible()), GridItem(.flexible())]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                header

                VStack(alignment: .leading, spacing: 14) {
                    Text("Modules")
                        .font(.title2.bold())

                    LazyVGrid(columns: columns, spacing: 14) {
                        ForEach(visibleModules) { module in
                            NavigationLink(destination: destinationView(for: module)) {
                                ModuleCardView(module: module, tintHex: space.tintHex)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                if let entitlementError {
                    Text(entitlementError).font(.footnote).foregroundStyle(.red)
                }
            }
            .padding(20)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(space.name)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: space.organizationId) {
            guard let organizationID = space.organizationId else { entitledModuleIDs = nil; return }
            do {
                entitledModuleIDs = try await OrganizationService().effectiveEntitlements(for: organizationID).enabledModuleIDs
                entitlementError = nil
            } catch {
                entitledModuleIDs = []
                entitlementError = error.localizedDescription
            }
        }
    }

    private var visibleModules: [SpaceModule] {
        guard space.organizationId != nil else { return space.modules }
        guard let entitledModuleIDs else { return [] }
        return space.modules.filter { entitledModuleIDs.contains($0.rawValue) }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .fill(Color(hex: space.tintHex).opacity(0.18))
                        .frame(width: 64, height: 64)

                    SpaceIconView(
                        emoji: space.emoji,
                        tintHex: space.tintHex,
                        font: .system(size: 28, weight: .semibold)
                    )
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text(space.name)
                        .font(.title.bold())
                    Text(space.subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            if let unreadCount = space.unreadCount {
                Label("\(unreadCount) unread pings", systemImage: "bell.badge.fill")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(Color(hex: space.tintHex))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        Capsule()
                            .fill(Color(hex: space.tintHex).opacity(0.12))
                    )
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [
                            Color(hex: space.tintHex).opacity(0.18),
                            Color(.secondarySystemBackground)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
        )
    }

    @ViewBuilder
    private func destinationView(for module: SpaceModule) -> some View {
        switch module {
        case .general:
            GeneralPlaceholderView(space: space)
        case .announcements:
            AnnouncementsView(space: space)
        case .rooms:
            RoomsView(space: space)
        case .photos:
            PhotosPlaceholderView(space: space)
        case .files:
            FilesPlaceholderView(space: space)
        case .polls:
            PollsPlaceholderView(space: space)
        case .events:
            EventsPlaceholderView(space: space)
        case .lists:
            ListsView(space: space)
        case .notes:
            NotesView(space: space)
        case .members:
            MembersPlaceholderView(space: space)
        case .settings:
            SpaceSettingsPlaceholderView(space: space)
        }
    }
}
