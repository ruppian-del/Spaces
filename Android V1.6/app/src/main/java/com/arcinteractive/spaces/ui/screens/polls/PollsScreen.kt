package com.arcinteractive.spaces.ui.screens.polls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceMember
import com.arcinteractive.spaces.data.model.SpacePoll
import com.arcinteractive.spaces.data.model.SpacePollOption
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollsScreen(
    space: Space,
    onBackPressed: () -> Unit,
    initialPollId: String? = null,
    viewModel: PollsViewModel = viewModel(factory = PollsViewModelFactory(space))
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var isPresentingCreatePoll by rememberSaveable { mutableStateOf(false) }
    var editingPoll by remember { mutableStateOf<SpacePoll?>(null) }

    LaunchedEffect(Unit) {
        viewModel.startListeningIfNeeded(context)
    }

    LaunchedEffect(uiState.polls, initialPollId) {
        val targetId = initialPollId ?: return@LaunchedEffect
        if (uiState.selectedPollId == null) {
            uiState.polls.firstOrNull { it.id == targetId }?.let(viewModel::openPoll)
        }
    }

    LaunchedEffect(uiState.lastErrorMessage) {
        val message = uiState.lastErrorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearLastErrorMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.selectedPoll == null) "Polls" else "Poll") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState.selectedPoll == null) onBackPressed() else viewModel.closePoll()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    uiState.selectedPoll?.takeIf(viewModel::canEdit)?.let { poll ->
                        IconButton(onClick = { editingPoll = poll }) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Edit Poll"
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.selectedPoll == null) {
                FloatingActionButton(onClick = { isPresentingCreatePoll = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Create Poll"
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        val selectedPoll = uiState.selectedPoll

        if (uiState.isLoading && selectedPoll == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (selectedPoll == null) {
            PollsList(
                polls = uiState.polls,
                tintHex = space.colorHex,
                onPollSelected = viewModel::openPoll,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            PollDetailScreen(
                poll = selectedPoll,
                currentUserId = uiState.currentUserId,
                tintHex = space.colorHex,
                canDelete = viewModel.canDelete(selectedPoll),
                canEdit = viewModel.canEdit(selectedPoll),
                isUpdatingVote = selectedPoll.id in uiState.votingPollIds,
                membersById = uiState.membersById,
                onVoteTap = { optionId -> viewModel.toggleVote(context, selectedPoll, optionId) },
                onEdit = { editingPoll = selectedPoll },
                onDelete = {
                    viewModel.deletePoll(context, selectedPoll) {
                        viewModel.closePoll()
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }

    if (isPresentingCreatePoll) {
        CreatePollDialog(
            onDismiss = { isPresentingCreatePoll = false },
            onCreate = { question, options, closesAt, allowMultipleVotes, anonymous ->
                viewModel.createPoll(
                    context = context,
                    question = question,
                    optionTexts = options,
                    closesAt = closesAt,
                    allowMultipleVotes = allowMultipleVotes,
                    anonymous = anonymous
                ) {
                    isPresentingCreatePoll = false
                }
            }
        )
    }

    editingPoll?.let { poll ->
        CreatePollDialog(
            poll = poll,
            onDismiss = { editingPoll = null },
            onCreate = { question, options, closesAt, allowMultipleVotes, anonymous ->
                viewModel.updatePoll(context, poll, question, options, closesAt, allowMultipleVotes, anonymous) {
                    editingPoll = null
                }
            }
        )
    }
}

@Composable
private fun PollsList(
    polls: List<SpacePoll>,
    tintHex: String,
    onPollSelected: (SpacePoll) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (polls.isEmpty()) {
            item {
                EmptyPollsState(modifier = Modifier.fillMaxWidth())
            }
        } else {
            items(polls, key = { it.id }) { poll ->
                PollCard(
                    poll = poll,
                    tintHex = tintHex,
                    onClick = { onPollSelected(poll) }
                )
            }
        }
    }
}

@Composable
private fun PollCard(
    poll: SpacePoll,
    tintHex: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Outlined.BarChart,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color(android.graphics.Color.parseColor(tintHex))
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = poll.question,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${poll.totalVotes} vote${if (poll.totalVotes == 1) "" else "s"} • ${poll.options.size} options",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (poll.isClosed) {
                    Text(
                        text = "Closed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            poll.options.firstOrNull()?.let { option ->
                PollProgressBar(
                    label = option.text,
                    progress = poll.percentage(option.id),
                    tintHex = tintHex
                )
            }
        }
    }
}

@Composable
private fun PollDetailScreen(
    poll: SpacePoll,
    currentUserId: String?,
    tintHex: String,
    canDelete: Boolean,
    canEdit: Boolean,
    isUpdatingVote: Boolean,
    membersById: Map<String, SpaceMember>,
    onVoteTap: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var shouldConfirmDelete by remember { mutableStateOf(false) }
    val selectedOptionIds = poll.selectedOptionIds(currentUserId)

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
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = poll.question,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Created by ${poll.createdByName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    poll.closesAt?.let { closesAt ->
                        Text(
                            text = if (poll.isClosed) {
                                "Closed ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(closesAt)}"
                            } else {
                                "Closes ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(closesAt)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (poll.allowMultipleVotes) {
                        Text(
                            text = "Multiple votes allowed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (poll.anonymous) {
                        Text(
                            text = "Anonymous poll",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (!poll.anonymous && poll.votes.isNotEmpty()) {
            item {
                Text(
                    text = "Who Voted",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(poll.votes, key = { "voter-${it.id}" }) { vote ->
                val member = membersById[vote.userId]
                val name = member?.displayName ?: vote.displayName?.takeIf { it.isNotBlank() } ?: "Member"
                val emoji = member?.emojiAvatar ?: vote.emojiAvatar?.takeIf { it.isNotBlank() } ?: "👤"
                val choices = poll.options.filter { it.id in vote.optionIds }.joinToString { it.text }
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { Text(choices) },
                    leadingContent = { Text(emoji, style = MaterialTheme.typography.headlineSmall) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                )
            }
        }

        item {
            Text(
                text = "Results",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        items(poll.options, key = { it.id }) { option ->
            PollOptionRow(
                option = option,
                poll = poll,
                tintHex = tintHex,
                isSelected = selectedOptionIds.contains(option.id),
                isUpdating = isUpdatingVote,
                onClick = { onVoteTap(option.id) }
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Total Votes",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Box(modifier = Modifier.weight(1f))
                    Text(
                        text = poll.totalVotes.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (canDelete) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { shouldConfirmDelete = true }) {
                        Text("Delete Poll", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (shouldConfirmDelete) {
        AlertDialog(
            onDismissRequest = { shouldConfirmDelete = false },
            title = { Text("Delete this poll?") },
            text = { Text("This poll will be hidden from the Space, but its Firestore document will remain for now.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        shouldConfirmDelete = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { shouldConfirmDelete = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PollOptionRow(
    option: SpacePollOption,
    poll: SpacePoll,
    tintHex: String,
    isSelected: Boolean,
    isUpdating: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !poll.isClosed && !isUpdating, onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (isSelected) {
                    Text(
                        text = "Selected",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(android.graphics.Color.parseColor(tintHex))
                    )
                }
                Text(
                    text = poll.votesCount(option.id).toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            PollProgressBar(
                label = null,
                progress = poll.percentage(option.id),
                tintHex = tintHex
            )

            Text(
                text = "${(poll.percentage(option.id) * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

        }
    }
}

@Composable
private fun PollProgressBar(
    label: String?,
    progress: Float,
    tintHex: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(999.dp)
                )
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .width((maxWidth * progress.coerceIn(0f, 1f)).coerceAtLeast(if (progress > 0f) 8.dp else 0.dp))
                        .height(10.dp)
                        .background(
                            color = Color(android.graphics.Color.parseColor(tintHex)),
                            shape = RoundedCornerShape(999.dp)
                        )
                )
            }
        }
    }
}

@Composable
private fun EmptyPollsState(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
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
                imageVector = Icons.Outlined.BarChart,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "No Polls Yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Create a poll to gather quick votes, decisions, and check-ins for this Space.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CreatePollDialog(
    poll: SpacePoll? = null,
    onDismiss: () -> Unit,
    onCreate: (question: String, options: List<String>, closesAt: Date?, allowMultipleVotes: Boolean, anonymous: Boolean) -> Unit
) {
    val initialOptions = poll?.options?.map { it.text }.orEmpty()
    var question by rememberSaveable(poll?.id) { mutableStateOf(poll?.question.orEmpty()) }
    var optionOne by rememberSaveable(poll?.id) { mutableStateOf(initialOptions.getOrNull(0).orEmpty()) }
    var optionTwo by rememberSaveable(poll?.id) { mutableStateOf(initialOptions.getOrNull(1).orEmpty()) }
    var optionThree by rememberSaveable(poll?.id) { mutableStateOf(initialOptions.getOrNull(2).orEmpty()) }
    var optionFour by rememberSaveable(poll?.id) { mutableStateOf(initialOptions.getOrNull(3).orEmpty()) }
    var allowMultipleVotes by rememberSaveable(poll?.id) { mutableStateOf(poll?.allowMultipleVotes ?: false) }
    var anonymous by rememberSaveable(poll?.id) { mutableStateOf(poll?.anonymous ?: false) }
    var autoClose by rememberSaveable(poll?.id) { mutableStateOf(poll?.closesAt != null) }
    var closesInDays by rememberSaveable { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(if (poll == null) "Create Poll" else "Edit Poll") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Question") }
                )
                OutlinedTextField(
                    value = optionOne,
                    onValueChange = { optionOne = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Option 1") }
                )
                OutlinedTextField(
                    value = optionTwo,
                    onValueChange = { optionTwo = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Option 2") }
                )
                OutlinedTextField(
                    value = optionThree,
                    onValueChange = { optionThree = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Option 3 (optional)") }
                )
                OutlinedTextField(
                    value = optionFour,
                    onValueChange = { optionFour = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Option 4 (optional)") }
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow multiple votes", modifier = Modifier.weight(1f))
                    Switch(checked = allowMultipleVotes, onCheckedChange = { allowMultipleVotes = it })
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Anonymous", modifier = Modifier.weight(1f))
                    Switch(checked = anonymous, onCheckedChange = { anonymous = it })
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Close poll automatically", modifier = Modifier.weight(1f))
                    Switch(checked = autoClose, onCheckedChange = { autoClose = it })
                }

                if (autoClose) {
                    OutlinedTextField(
                        value = closesInDays,
                        onValueChange = { closesInDays = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Closes in days") },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val closesAt = if (autoClose) {
                        val days = closesInDays.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, days) }.time
                    } else {
                        null
                    }
                    onCreate(
                        question,
                        listOf(optionOne, optionTwo, optionThree, optionFour),
                        closesAt,
                        allowMultipleVotes,
                        anonymous
                    )
                },
                enabled = question.trim().isNotEmpty() && listOf(optionOne, optionTwo, optionThree, optionFour).count { it.trim().isNotEmpty() } >= 2
            ) {
                Text(if (poll == null) "Create" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private class PollsViewModelFactory(
    private val space: Space
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PollsViewModel(space) as T
    }
}
