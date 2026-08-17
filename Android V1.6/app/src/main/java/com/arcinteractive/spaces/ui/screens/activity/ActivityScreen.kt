package com.arcinteractive.spaces.ui.screens.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcinteractive.spaces.data.model.ActivityItem
import com.arcinteractive.spaces.data.model.ActivityTargetType
import com.arcinteractive.spaces.data.model.ActivityType
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.ui.navigation.Destination

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    spaces: List<Space>,
    onNavigateToRoute: (String) -> Unit,
    viewModel: ActivityViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val selectedItem = uiState.selectedItem
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(spaces) {
        viewModel.startListening(context, spaces)
    }

    LaunchedEffect(uiState.lastErrorMessage) {
        val message = uiState.lastErrorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearLastErrorMessage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
                navigationIcon = if (selectedItem != null) {
                    {
                        IconButton(onClick = viewModel::closeItem) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                } else {
                    {}
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        if (selectedItem == null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (uiState.isLoading && uiState.items.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Loading activity…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (uiState.items.isEmpty()) {
                    item {
                        EmptyActivityState()
                    }
                } else {
                    viewModel.groupedItems.forEach { group ->
                        item(group.first.label) {
                            Text(
                                text = group.first.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                        }

                        items(group.second, key = { it.id }) { item ->
                        ActivityRow(
                            item = item,
                            isUnread = item.isUnread(uiState.currentUserId),
                            onClick = {
                                    viewModel.markRead(context, item)
                                    val route = activityRouteFor(item, spaces)
                                    if (route != null) {
                                        onNavigateToRoute(route)
                                    } else {
                                        viewModel.openItem(item)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } else {
            ActivityDetailScreen(
                item = selectedItem,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }
}

@Composable
private fun EmptyActivityState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.TrendingUp,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "No activity yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "New Space activity will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActivityRow(
    item: ActivityItem,
    isUnread: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = if (isUnread) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = if (isUnread) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        shape = CircleShape
                    )
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = item.actorEmoji ?: item.spaceEmoji, fontSize = 24.sp)
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = item.primaryText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = item.timestampText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.spaceName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = activityTypeLabel(item.type),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isUnread) {
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .padding(4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityDetailScreen(
    item: ActivityItem,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = item.actorEmoji ?: item.spaceEmoji, fontSize = 32.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = item.primaryText,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        item.subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun activityRouteFor(item: ActivityItem, spaces: List<Space>): String? {
    if (spaces.none { it.id == item.spaceId }) return null
    return when (item.targetType) {
        ActivityTargetType.General -> Destination.GeneralPlaceholder.routeFor(item.spaceId)
        ActivityTargetType.Photos -> Destination.PhotosPlaceholder.routeFor(item.spaceId)
        ActivityTargetType.Files -> Destination.FilesPlaceholder.routeFor(item.spaceId)
        ActivityTargetType.Polls -> Destination.PollsPlaceholder.routeFor(item.spaceId)
        ActivityTargetType.Events -> Destination.EventsPlaceholder.routeFor(item.spaceId)
        ActivityTargetType.Members -> Destination.MembersPlaceholder.routeFor(item.spaceId)
        ActivityTargetType.Announcements -> Destination.Announcements.routeFor(item.spaceId)
        ActivityTargetType.Rooms -> Destination.Rooms.routeFor(item.spaceId, item.targetId)
        ActivityTargetType.Lists -> Destination.Lists.routeFor(item.spaceId, item.targetId)
        ActivityTargetType.Notes -> Destination.Notes.routeFor(item.spaceId, item.targetId)
        ActivityTargetType.Space, null -> Destination.SpaceDetail.routeFor(item.spaceId)
    }
}

private fun activityTypeLabel(type: ActivityType): String {
    return when (type) {
        ActivityType.SpaceCreated -> "Space"
        ActivityType.MemberJoined -> "Member"
        ActivityType.MessageSent -> "Message"
        ActivityType.PhotoShared -> "Photo"
        ActivityType.VideoShared -> "Video"
        ActivityType.FileUploaded -> "File"
        ActivityType.PollCreated -> "Poll"
        ActivityType.PollVoted -> "Vote"
        ActivityType.EventCreated -> "Event"
        ActivityType.EventUpdated -> "Event"
        ActivityType.ReactionAdded -> "Reaction"
        ActivityType.ReplyAdded -> "Reply"
        ActivityType.AnnouncementCreated -> "Announcement"
        ActivityType.RoomCreated -> "Room"
        ActivityType.RoomMessageSent -> "Room"
        ActivityType.ListCreated -> "List"
        ActivityType.NoteCreated -> "Note"
    }
}
