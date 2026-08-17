import SwiftUI
import FirebaseFirestore

struct CreateSpaceSheet: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var viewModel = CreateSpaceViewModel()
    @FocusState private var isEmojiFieldFocused: Bool
    @State private var isShowingInviteInfo = false
    @State private var organization: Organization?
    @State private var organizationListener: ListenerRegistration?
    @State private var isOrganizationOwned = false
    private let organizationService = OrganizationService()

    let onCreate: (_ name: String, _ emoji: String, _ tintHex: String, _ description: String, _ template: SpaceTemplate, _ enabledModules: [SpaceModule], _ organizationID: String?) -> Void

    var body: some View {
        NavigationView {
            Form {
                Section("Details") {
                    TextField("Space Name", text: $viewModel.name)
                        .textInputAutocapitalization(.words)

                    HStack(alignment: .center, spacing: 12) {
                        Button {
                            isEmojiFieldFocused = true
                        } label: {
                            Text(viewModel.displayEmoji)
                                .font(.system(size: 28))
                                .frame(width: 56, height: 56)
                                .background(Color(.secondarySystemBackground))
                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Space Emoji")

                        TextField("Emoji", text: $viewModel.emoji)
                            .focused($isEmojiFieldFocused)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .onChange(of: viewModel.emoji) { _ in
                                viewModel.sanitizeEmojiInput()
                            }
                    }

                    ColorPicker("Space Color", selection: $viewModel.color, supportsOpacity: false)

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Description")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)

                        TextEditor(text: $viewModel.description)
                            .frame(minHeight: 88)
                    }

                    Picker("Template", selection: Binding(
                        get: { viewModel.template },
                        set: { viewModel.updateTemplate($0) }
                    )) {
                        ForEach(SpaceTemplate.allCases) { template in
                            VStack(alignment: .leading) {
                                Text(template.rawValue)
                                Text(template.subtitle)
                            }
                            .tag(template)
                        }
                    }

                }

                if let organization {
                    Section {
                        Picker("Space belongs to", selection: $isOrganizationOwned) {
                            Text("Personal").tag(false)
                            Text(organization.name).tag(true)
                        }
                    } header: {
                        Text("Ownership")
                    } footer: {
                        Text("Organization ownership does not add anyone. Members are invited to this Space normally.")
                    }
                }

                Section {
                    if viewModel.template == .custom {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Required Modules")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)

                            Text(["Space Pings", "Media", "Members", "Settings"].joined(separator: " • "))
                                .font(.subheadline.weight(.medium))
                        }
                        .padding(.vertical, 4)

                        ForEach(SpaceModule.optionalModules) { module in
                            Toggle(
                                isOn: Binding(
                                    get: { viewModel.isModuleEnabled(module) },
                                    set: { viewModel.setModuleEnabled(module, isEnabled: $0) }
                                )
                            ) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("\(module.emoji) \(module.title)")
                                    Text(module.description)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Included Modules")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)

                            Text(
                                viewModel.enabledModules
                                    .map(\.title)
                                    .joined(separator: " • ")
                            )
                            .font(.subheadline.weight(.medium))
                        }
                        .padding(.vertical, 4)
                    }
                } header: {
                    Text("Modules")
                } footer: {
                    if viewModel.template == .custom {
                        Text("Required modules always remain enabled. Optional modules can be enabled now or later from Space Settings.")
                    }
                }

                Section("Invite Members") {
                    Button("Invite Members") {
                        isShowingInviteInfo = true
                    }
                    .foregroundStyle(.secondary)

                    Text("Optional for now. You can invite people later.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Create Space")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button("Create Space") {
                        onCreate(
                            viewModel.name,
                            viewModel.displayEmoji,
                            viewModel.tintHex,
                            viewModel.description,
                            viewModel.template,
                            viewModel.enabledModules,
                            isOrganizationOwned ? organization?.id : nil
                        )
                        dismiss()
                    }
                    .disabled(!viewModel.isCreateEnabled)
                }
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .onAppear {
            organizationListener = organizationService.listenToOrganizationsForCurrentUser { result in
                organization = try? result.get().sorted { $0.name < $1.name }.first
            }
        }
        .onDisappear { organizationListener?.remove() }
        .alert("Invite Members", isPresented: $isShowingInviteInfo) {
            Button("OK", role: .cancel) { }
        } message: {
            Text("Create the Space first, then invite members from the Members screen.")
        }
    }
}
