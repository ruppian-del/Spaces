package com.arcinteractive.spaces.ui.screens.events

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcinteractive.spaces.data.calendar.DeviceCalendarService
import com.arcinteractive.spaces.data.model.EditableSpaceEvent
import com.arcinteractive.spaces.data.model.EventEditorMode
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceEvent
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    space: Space,
    onBackPressed: () -> Unit,
    initialEventId: String? = null,
    viewModel: EventsViewModel = viewModel(factory = EventsViewModelFactory(space))
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val selectedEvent = uiState.selectedEvent
    val snackbarHostState = remember { SnackbarHostState() }
    val calendarService = remember { DeviceCalendarService() }
    var calendarMessage by remember { mutableStateOf<String?>(null) }

    val addToCalendarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        calendarMessage = if (result.resultCode == Activity.RESULT_OK) {
            "Event added to your calendar."
        } else {
            "Calendar export closed without saving."
        }
    }

    LaunchedEffect(space.id) {
        viewModel.startListening(context)
    }

    LaunchedEffect(uiState.events, initialEventId) {
        val targetId = initialEventId ?: return@LaunchedEffect
        if (uiState.selectedEvent == null) {
            uiState.events.firstOrNull { it.id == targetId }?.let { viewModel.openEvent(context, it) }
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
    }

    LaunchedEffect(calendarMessage) {
        val message = calendarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        calendarMessage = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.selectedEvent == null) "Events" else "Event") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState.selectedEvent == null) {
                                onBackPressed()
                            } else {
                                viewModel.closeEvent()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.selectedEvent == null && uiState.canCreateEvents) {
                FloatingActionButton(onClick = viewModel::presentCreateEvent) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Create Event"
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (selectedEvent == null) {
            EventsList(
                events = uiState.events,
                isLoading = uiState.isLoading,
                spaceName = space.name,
                tintHex = space.colorHex,
                onEventSelected = { viewModel.openEvent(context, it) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            EventDetailScreen(
                event = selectedEvent,
                canManage = uiState.canManageSelectedEvent,
                isDeleting = uiState.isDeleting,
                onEdit = { viewModel.presentEditEvent(selectedEvent) },
                onDelete = { viewModel.deleteEvent(context, selectedEvent) },
                onAddToCalendar = { event ->
                    val intent = runCatching { calendarService.buildInsertIntent(event) }.getOrNull()
                    if (intent == null) {
                        calendarMessage = "This event could not be converted into a calendar date."
                        return@EventDetailScreen
                    }
                    if (intent.resolveActivity(context.packageManager) == null) {
                        calendarMessage = "No calendar app is available on this device."
                        return@EventDetailScreen
                    }
                    addToCalendarLauncher.launch(intent)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }

    val editorMode = uiState.editorMode
    if (editorMode != null) {
        EventEditorSheet(
            mode = editorMode,
            isSaving = uiState.isSaving,
            onDismiss = viewModel::dismissEditor,
            onSave = { draft, event ->
                viewModel.saveEvent(context, draft, event)
            }
        )
    }
}

@Composable
private fun EventsList(
    events: List<SpaceEvent>,
    isLoading: Boolean,
    spaceName: String,
    tintHex: String,
    onEventSelected: (SpaceEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isLoading && events.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Loading events…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@LazyColumn
        }

        if (events.isEmpty()) {
            item {
                EmptyEventsState(
                    spaceName = spaceName,
                    tintHex = tintHex,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            return@LazyColumn
        }

        items(events, key = { it.id }) { event ->
            EventRow(
                event = event,
                tintHex = tintHex,
                onClick = { onEventSelected(event) }
            )
        }
    }
}

@Composable
private fun EmptyEventsState(
    spaceName: String,
    tintHex: String,
    modifier: Modifier = Modifier
) {
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
                imageVector = Icons.Outlined.Event,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = Color(android.graphics.Color.parseColor(tintHex))
            )
            Text(
                text = "No events yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Create the first event for $spaceName.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EventRow(
    event: SpaceEvent,
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
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .background(
                        color = Color(android.graphics.Color.parseColor(tintHex)).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Event,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color(android.graphics.Color.parseColor(tintHex))
                )
                Text(
                    text = if (event.allDay) "ALL DAY" else shortDayFormatter.format(event.startDate).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(android.graphics.Color.parseColor(tintHex)),
                    fontWeight = FontWeight.SemiBold
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = event.dateText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = event.timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (event.location.isNotBlank()) {
                    Text(
                        text = event.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EventDetailScreen(
    event: SpaceEvent,
    canManage: Boolean,
    isDeleting: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddToCalendar: (SpaceEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteConfirmation by remember(event.id) { mutableStateOf(false) }

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
                        text = event.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = event.dateText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = event.timeText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (event.location.isNotBlank()) {
                        Text(
                            text = event.location,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (event.description.isNotBlank()) {
                        Text(
                            text = event.description,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Text(
                        text = "Created by ${event.createdByName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Button(
                onClick = { onAddToCalendar(event) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add to Calendar")
            }
        }

        if (canManage) {
            item {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Edit Event")
                }
            }

            item {
                OutlinedButton(
                    onClick = { showDeleteConfirmation = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isDeleting
                ) {
                    Text(if (isDeleting) "Deleting..." else "Delete Event", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete this event?") },
            text = { Text("This event will be removed from the Space.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditorSheet(
    mode: EventEditorMode,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (EditableSpaceEvent, SpaceEvent?) -> Unit
) {
    var draft by remember(mode) {
        mutableStateOf(
            when (mode) {
                EventEditorMode.Create -> EditableSpaceEvent()
                is EventEditorMode.Edit -> EditableSpaceEvent.fromEvent(mode.event)
            }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = mode.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            OutlinedTextField(
                value = draft.title,
                onValueChange = { draft = draft.copy(title = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") },
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("All-day", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = draft.allDay,
                    onCheckedChange = { checked ->
                        draft = draft.copy(allDay = checked)
                    }
                )
            }

            DateField(
                label = "Date",
                value = draft.startDate,
                onDateSelected = { selected ->
                    draft = draft.copy(
                        startDate = updateDateKeepingTime(draft.startDate, selected),
                        endDate = adjustEndDate(
                            start = updateDateKeepingTime(draft.startDate, selected),
                            currentEnd = updateDateKeepingTime(draft.endDate, selected)
                        )
                    )
                }
            )

            if (draft.allDay) {
                DateField(
                    label = "Ends",
                    value = draft.endDate,
                    onDateSelected = { selected ->
                        val adjustedEnd = endOfDay(selected)
                        draft = draft.copy(endDate = if (adjustedEnd.before(draft.startDate)) draft.startDate else adjustedEnd)
                    }
                )
            } else {
                TimeField(
                    label = "Start time",
                    value = draft.startDate,
                    onTimeSelected = { selected ->
                        val newStart = updateTimeKeepingDate(draft.startDate, selected)
                        draft = draft.copy(
                            startDate = newStart,
                            endDate = adjustEndDate(newStart, draft.endDate)
                        )
                    }
                )
                TimeField(
                    label = "End time",
                    value = draft.endDate,
                    onTimeSelected = { selected ->
                        draft = draft.copy(endDate = updateTimeKeepingDate(draft.endDate, selected))
                    }
                )
            }

            OutlinedTextField(
                value = draft.location,
                onValueChange = { draft = draft.copy(location = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Location") },
                singleLine = true
            )

            OutlinedTextField(
                value = draft.description,
                onValueChange = { draft = draft.copy(description = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Description") },
                minLines = 4
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val editingEvent = (mode as? EventEditorMode.Edit)?.event
                        onSave(draft, editingEvent)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = draft.canSave && !isSaving
                ) {
                    Text(if (isSaving) "Saving..." else "Save")
                }
            }
        }
    }
}

@Composable
private fun DateField(
    label: String,
    value: Date,
    onDateSelected: (Date) -> Unit
) {
    val context = LocalContext.current
    val calendar = remember(value) { Calendar.getInstance().apply { time = value } }

    OutlinedButton(
        onClick = {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    val selected = Calendar.getInstance().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, day)
                    }.time
                    onDateSelected(selected)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(dateFormatter.format(value), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun TimeField(
    label: String,
    value: Date,
    onTimeSelected: (Date) -> Unit
) {
    val context = LocalContext.current
    val calendar = remember(value) { Calendar.getInstance().apply { time = value } }

    OutlinedButton(
        onClick = {
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val selected = Calendar.getInstance().apply {
                        time = value
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                    }.time
                    onTimeSelected(selected)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                false
            ).show()
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(timeFormatter.format(value), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun updateDateKeepingTime(current: Date, selectedDate: Date): Date {
    val currentCalendar = Calendar.getInstance().apply { time = current }
    val selectedCalendar = Calendar.getInstance().apply { time = selectedDate }
    selectedCalendar.set(Calendar.HOUR_OF_DAY, currentCalendar.get(Calendar.HOUR_OF_DAY))
    selectedCalendar.set(Calendar.MINUTE, currentCalendar.get(Calendar.MINUTE))
    selectedCalendar.set(Calendar.SECOND, currentCalendar.get(Calendar.SECOND))
    return selectedCalendar.time
}

private fun updateTimeKeepingDate(current: Date, selectedTime: Date): Date {
    val currentCalendar = Calendar.getInstance().apply { time = current }
    val selectedCalendar = Calendar.getInstance().apply { time = selectedTime }
    currentCalendar.set(Calendar.HOUR_OF_DAY, selectedCalendar.get(Calendar.HOUR_OF_DAY))
    currentCalendar.set(Calendar.MINUTE, selectedCalendar.get(Calendar.MINUTE))
    currentCalendar.set(Calendar.SECOND, 0)
    return currentCalendar.time
}

private fun adjustEndDate(start: Date, currentEnd: Date): Date {
    return if (currentEnd.before(start)) {
        Calendar.getInstance().apply {
            time = start
            add(Calendar.HOUR_OF_DAY, 1)
        }.time
    } else {
        currentEnd
    }
}

private fun endOfDay(date: Date): Date {
    return Calendar.getInstance().apply {
        time = date
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 0)
    }.time
}

private val dateFormatter: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
private val timeFormatter: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
private val shortDayFormatter = SimpleDateFormat("EEE", Locale.getDefault())

private class EventsViewModelFactory(
    private val space: Space
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return EventsViewModel(space) as T
    }
}
