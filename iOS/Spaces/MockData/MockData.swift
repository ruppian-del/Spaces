import Foundation

enum MockData {
    static let spaces: [Space] = [
        Space(
            id: "rupp-family",
            name: "Rupp Family",
            emoji: "🏡",
            tintHex: "#3A6EA5",
            description: "3 new messages",
            template: .family,
            ownerId: "mock-ian",
            memberIds: ["mock-ian", "mock-mom", "mock-dad", "mock-sarah"],
            unreadCount: 3,
            enabledModules: SpaceTemplate.family.defaultEnabledModules,
            moduleOrder: SpaceTemplate.family.defaultModuleOrder
        ),
        Space(
            id: "arcinteractive",
            name: "ArcInteractive",
            emoji: "⚡",
            tintHex: "#2F855A",
            description: "Standup starts at 9:00 AM",
            template: .business,
            ownerId: "mock-ian",
            memberIds: ["mock-ian", "mock-team"],
            unreadCount: 7,
            enabledModules: SpaceTemplate.business.defaultEnabledModules,
            moduleOrder: SpaceTemplate.business.defaultModuleOrder
        ),
        Space(
            id: "friends",
            name: "Friends",
            emoji: "🫶",
            tintHex: "#D97706",
            description: "Weekend trip ideas",
            template: .friends,
            ownerId: "mock-ian",
            memberIds: ["mock-ian", "mock-sarah", "mock-jordan"],
            unreadCount: nil,
            enabledModules: SpaceTemplate.friends.defaultEnabledModules,
            moduleOrder: SpaceTemplate.friends.defaultModuleOrder
        ),
        Space(
            id: "photography-club",
            name: "Photography Club",
            emoji: "📷",
            tintHex: "#7C3AED",
            description: "Monthly challenge is live",
            template: .community,
            ownerId: "mock-ian",
            memberIds: ["mock-ian", "mock-club"],
            unreadCount: 1,
            enabledModules: SpaceTemplate.community.defaultEnabledModules,
            moduleOrder: SpaceTemplate.community.defaultModuleOrder
        )
    ]

    static let pings: [Ping] = [
        Ping(
            id: "mock-ping-1",
            participantIds: ["mock-ian", "mock-mom"],
            participantNames: ["Ian", "Mom"],
            participantEmojis: ["🧑‍💻", "👩"],
            lastMessageAt: Date().addingTimeInterval(-300),
            lastMessagePreviewType: MessageType.text.rawValue,
            createdAt: Date().addingTimeInterval(-3600),
            updatedAt: Date().addingTimeInterval(-300),
            unreadCount: 1
        ),
        Ping(
            id: "mock-ping-2",
            participantIds: ["mock-ian", "mock-sarah"],
            participantNames: ["Ian", "Sarah"],
            participantEmojis: ["🧑‍💻", "👩‍🦰"],
            lastMessageAt: Date().addingTimeInterval(-1140),
            lastMessagePreviewType: MessageType.text.rawValue,
            createdAt: Date().addingTimeInterval(-7200),
            updatedAt: Date().addingTimeInterval(-1140),
            unreadCount: 2
        ),
        Ping(
            id: "mock-ping-3",
            participantIds: ["mock-dad", "mock-ian"],
            participantNames: ["Dad", "Ian"],
            participantEmojis: ["👨", "🧑‍💻"],
            lastMessageAt: Date().addingTimeInterval(-3600),
            lastMessagePreviewType: MessageType.text.rawValue,
            createdAt: Date().addingTimeInterval(-10800),
            updatedAt: Date().addingTimeInterval(-3600),
            unreadCount: 0
        )
    ]

    static let activity: [ActivityItem] = []

    static func generalMessages(for space: Space) -> [SpaceMessage] {
        [
            SpaceMessage(
                id: UUID(),
                senderName: "Mom",
                text: "Who's bringing dessert?",
                timestamp: "6:42 PM",
                isOutgoing: false,
                deliveryStatus: nil
            ),
            SpaceMessage(
                id: UUID(),
                senderName: "Ian",
                text: "I'll grill.",
                timestamp: "6:44 PM",
                isOutgoing: true,
                deliveryStatus: "Delivered"
            ),
            SpaceMessage(
                id: UUID(),
                senderName: "Dad",
                text: "I'll bring drinks.",
                timestamp: "6:46 PM",
                isOutgoing: false,
                deliveryStatus: nil
            ),
            SpaceMessage(
                id: UUID(),
                senderName: "Sarah",
                text: "I can bring chips.",
                timestamp: "6:47 PM",
                isOutgoing: false,
                deliveryStatus: nil
            ),
            SpaceMessage(
                id: UUID(),
                senderName: "Mom",
                media: SpaceMedia(
                    id: UUID(),
                    type: .image,
                    placeholderImageName: "birthday.cake.fill",
                    caption: "I found the cake idea.",
                    senderName: "Mom",
                    timestamp: "6:48 PM"
                ),
                timestamp: "6:48 PM",
                isOutgoing: false,
                deliveryStatus: nil
            ),
            SpaceMessage(
                id: UUID(),
                senderName: "Sarah",
                media: SpaceMedia(
                    id: UUID(),
                    type: .meme,
                    placeholderImageName: "face.smiling.fill",
                    caption: "Mood if Dad forgets the ice again.",
                    senderName: "Sarah",
                    timestamp: "6:49 PM"
                ),
                timestamp: "6:49 PM",
                isOutgoing: false,
                deliveryStatus: nil
            ),
            SpaceMessage(
                id: UUID(),
                senderName: "Ian",
                text: "Perfect. I'll fire up the grill around 5.",
                timestamp: "6:50 PM",
                isOutgoing: true,
                deliveryStatus: "Seen"
            ),
            SpaceMessage(
                id: UUID(),
                senderName: "Ian",
                media: SpaceMedia(
                    id: UUID(),
                    type: .video,
                    placeholderImageName: "play.tv.fill",
                    caption: "Quick grill setup preview.",
                    senderName: "Ian",
                    timestamp: "6:51 PM"
                ),
                timestamp: "6:51 PM",
                isOutgoing: true,
                deliveryStatus: "Delivered"
            )
        ]
    }

    static func photosMedia(for space: Space) -> [SpaceMedia] {
        generalMessages(for: space)
            .compactMap(\.media)
            .filter { $0.type.isPhotosModuleSupported }
    }

    static func spaceEvents(for space: Space) -> [SpaceEvent] {
        []
    }

    static func spaceMembers(for space: Space) -> [SpaceMember] {
        [
            SpaceMember(
                id: "\(space.id)-member-ian",
                displayName: "Ian",
                emojiAvatar: "🧑‍💻",
                role: .owner,
                status: "Active"
            ),
            SpaceMember(
                id: "\(space.id)-member-mom",
                displayName: "Mom",
                emojiAvatar: "👩",
                role: .member,
                status: "Active"
            ),
            SpaceMember(
                id: "\(space.id)-member-dad",
                displayName: "Dad",
                emojiAvatar: "👨",
                role: .member,
                status: "Active"
            ),
            SpaceMember(
                id: "\(space.id)-member-sarah",
                displayName: "Sarah",
                emojiAvatar: "👩‍🦰",
                role: .admin,
                status: "Away"
            )
        ]
    }

    static func pingMessages(for ping: Ping) -> [SpaceMessage] {
        switch ping.title(for: "mock-ian") {
        case "Mom":
            return [
                SpaceMessage(id: UUID(), senderName: "Mom", text: "Hey, call me when you can.", timestamp: "6:12 PM", isOutgoing: false, deliveryStatus: nil),
                SpaceMessage(id: UUID(), senderName: "Ian", text: "I can in about 10 minutes.", timestamp: "6:14 PM", isOutgoing: true, deliveryStatus: "Delivered"),
                SpaceMessage(id: UUID(), senderName: "Mom", text: "Perfect.", timestamp: "6:15 PM", isOutgoing: false, deliveryStatus: nil),
                SpaceMessage(id: UUID(), senderName: "Mom", text: "Call me when you can", timestamp: "6:21 PM", isOutgoing: false, deliveryStatus: nil)
            ]
        case "Sarah":
            return [
                SpaceMessage(id: UUID(), senderName: "Sarah", text: "Did you need the photo?", timestamp: "4:40 PM", isOutgoing: false, deliveryStatus: nil),
                SpaceMessage(id: UUID(), senderName: "Ian", text: "Yeah, where did you drop it?", timestamp: "4:42 PM", isOutgoing: true, deliveryStatus: "Seen"),
                SpaceMessage(id: UUID(), senderName: "Sarah", text: "I sent it in the Family Space", timestamp: "4:43 PM", isOutgoing: false, deliveryStatus: nil)
            ]
        default:
            return [
                SpaceMessage(id: UUID(), senderName: "Dad", text: "Take a look at the grill cover.", timestamp: "1:08 PM", isOutgoing: false, deliveryStatus: nil),
                SpaceMessage(id: UUID(), senderName: "Ian", text: "Just saw it.", timestamp: "1:12 PM", isOutgoing: true, deliveryStatus: "Delivered"),
                SpaceMessage(id: UUID(), senderName: "Dad", text: "Looks good", timestamp: "1:15 PM", isOutgoing: false, deliveryStatus: nil)
            ]
        }
    }
}
