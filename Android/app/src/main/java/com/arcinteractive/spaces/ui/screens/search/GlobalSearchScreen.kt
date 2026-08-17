package com.arcinteractive.spaces.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcinteractive.spaces.data.model.Space

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun GlobalSearchScreen(
    spaces: List<Space>,
    onBack: () -> Unit,
    onOpenSpace: (Space) -> Unit,
    onOpenSpaceMessages: (Space) -> Unit,
    onOpenPing: (String) -> Unit,
    viewModel: GlobalSearchViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(spaces) {
        viewModel.start(context, spaces)
    }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearErrorMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = { viewModel.updateQuery(context, it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search Spaces, people, and messages") },
                    singleLine = true
                )
            }

            if (uiState.query.trim().isEmpty()) {
                item {
                    EmptySearchState(
                        title = "Search everything",
                        subtitle = "Search Spaces, people, Pings, and recent messages."
                    )
                }
            } else {
                if (uiState.spaceResults.isNotEmpty()) {
                    item { SearchSectionTitle("Spaces") }
                    items(uiState.spaceResults, key = { it.id }) { space ->
                        SearchRow(
                            leading = space.emoji,
                            title = space.name,
                            subtitle = space.description,
                            onClick = { onOpenSpace(space) }
                        )
                    }
                }

                if (uiState.pingResults.isNotEmpty() || uiState.peopleResults.isNotEmpty()) {
                    item { SearchSectionTitle("Pings & People") }
                    items(uiState.pingResults, key = { it.id }) { ping ->
                        SearchRow(
                            leading = ping.emoji(uiState.currentUserId),
                            title = ping.title(uiState.currentUserId),
                            subtitle = "Existing Ping",
                            onClick = { onOpenPing(ping.id) }
                        )
                    }
                    items(uiState.peopleResults, key = { it.id }) { participant ->
                        SearchRow(
                            leading = participant.emojiAvatar,
                            title = participant.displayName,
                            subtitle = "Start a Ping",
                            onClick = {
                                viewModel.createOrOpenPing(context, participant) { ping ->
                                    if (ping != null) {
                                        onOpenPing(ping.id)
                                    }
                                }
                            }
                        )
                    }
                }

                if (uiState.isLoading) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            tonalElevation = 1.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator()
                                Text("Searching recent messages…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else if (uiState.messageResults.isNotEmpty()) {
                    item { SearchSectionTitle("Messages") }
                    items(uiState.messageResults, key = { it.id }) { result ->
                        SearchRow(
                            leading = "\uD83D\uDCAC",
                            title = result.title,
                            subtitle = "${result.subtitle} • ${result.preview}",
                            onClick = {
                                when (result.sourceType) {
                                    GlobalMessageSourceType.Space -> {
                                        spaces.firstOrNull { it.id == result.sourceId }?.let(onOpenSpaceMessages)
                                    }
                                    GlobalMessageSourceType.Ping -> onOpenPing(result.sourceId)
                                }
                            }
                        )
                    }
                }

                if (!uiState.isLoading &&
                    uiState.spaceResults.isEmpty() &&
                    uiState.pingResults.isEmpty() &&
                    uiState.peopleResults.isEmpty() &&
                    uiState.messageResults.isEmpty()
                ) {
                    item {
                        EmptySearchState(
                            title = "No results",
                            subtitle = "Try a different name, person, or message keyword."
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun SearchRow(
    leading: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(leading, fontSize = 24.sp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptySearchState(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
