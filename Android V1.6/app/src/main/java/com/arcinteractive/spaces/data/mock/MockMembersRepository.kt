package com.arcinteractive.spaces.data.mock

import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceMember
import com.arcinteractive.spaces.data.model.SpaceMemberRole

object MockMembersRepository {
    fun membersFor(space: Space): List<SpaceMember> {
        return listOf(
            SpaceMember(
                id = "${space.id}-member-ian",
                displayName = "Ian",
                emojiAvatar = "\uD83E\uDDD1\u200D\uD83D\uDCBB",
                role = SpaceMemberRole.Owner,
                status = "Active"
            ),
            SpaceMember(
                id = "${space.id}-member-mom",
                displayName = "Mom",
                emojiAvatar = "👩",
                role = SpaceMemberRole.Member,
                status = "Active"
            ),
            SpaceMember(
                id = "${space.id}-member-dad",
                displayName = "Dad",
                emojiAvatar = "👨",
                role = SpaceMemberRole.Member,
                status = "Active"
            ),
            SpaceMember(
                id = "${space.id}-member-sarah",
                displayName = "Sarah",
                emojiAvatar = "👩‍🦰",
                role = SpaceMemberRole.Admin,
                status = "Away"
            )
        )
    }
}
