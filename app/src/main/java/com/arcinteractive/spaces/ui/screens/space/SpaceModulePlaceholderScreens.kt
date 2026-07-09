package com.arcinteractive.spaces.ui.screens.space

import com.arcinteractive.spaces.ui.screens.general.GeneralScreen
import com.arcinteractive.spaces.ui.screens.events.EventsScreen
import com.arcinteractive.spaces.ui.screens.files.FilesScreen
import com.arcinteractive.spaces.ui.screens.members.MembersScreen
import com.arcinteractive.spaces.ui.screens.photos.PhotosScreen
import com.arcinteractive.spaces.ui.screens.polls.PollsScreen
import com.arcinteractive.spaces.ui.screens.settings.SpaceSettingsScreen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcinteractive.spaces.data.model.Space

@Composable
fun GeneralPlaceholderScreen(space: Space, onBackPressed: () -> Unit) {
    GeneralScreen(space = space, onBackPressed = onBackPressed)
}

@Composable
fun PhotosPlaceholderScreen(space: Space, onBackPressed: () -> Unit) {
    PhotosScreen(space = space, onBackPressed = onBackPressed)
}

@Composable
fun FilesPlaceholderScreen(space: Space, onBackPressed: () -> Unit) {
    FilesScreen(space = space, onBackPressed = onBackPressed)
}

@Composable
fun PollsPlaceholderScreen(space: Space, onBackPressed: () -> Unit) {
    PollsScreen(space = space, onBackPressed = onBackPressed)
}

@Composable
fun EventsPlaceholderScreen(space: Space, onBackPressed: () -> Unit) {
    EventsScreen(space = space, onBackPressed = onBackPressed)
}

@Composable
fun MembersPlaceholderScreen(space: Space, onBackPressed: () -> Unit) {
    MembersScreen(space = space, onBackPressed = onBackPressed)
}

@Composable
fun SpaceSettingsPlaceholderScreen(space: Space, onBackPressed: () -> Unit) {
    SpaceSettingsScreen(space = space, onBackPressed = onBackPressed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpaceModulePlaceholderScaffold(
    title: String,
    emoji: String,
    description: String,
    onBackPressed: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = emoji, fontSize = 32.sp)
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
