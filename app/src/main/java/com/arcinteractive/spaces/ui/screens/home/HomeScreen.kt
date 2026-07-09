package com.arcinteractive.spaces.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.ui.components.SectionHeader
import com.arcinteractive.spaces.ui.components.SpaceCard
import com.arcinteractive.spaces.ui.navigation.AppViewModel

@Composable
fun HomeScreen(
    currentUserId: String?,
    onSpaceSelected: (Space) -> Unit,
    onSearchSelected: () -> Unit,
    appViewModel: AppViewModel,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val appUiState by appViewModel.uiState.collectAsState()
    val isShowingCreateSheet = remember { mutableStateOf(false) }
    val isShowingJoinSheet = remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(currentUserId) {
        viewModel.handleAuthState(context, currentUserId)
    }

    LaunchedEffect(uiState.lastErrorMessage) {
        val message = uiState.lastErrorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearLastErrorMessage()
    }

    LaunchedEffect(uiState.lastSuccessMessage) {
        val message = uiState.lastSuccessMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearLastSuccessMessage()
    }

    LaunchedEffect(appUiState.pendingInviteCode) {
        val code = appUiState.pendingInviteCode ?: return@LaunchedEffect
        appViewModel.clearPendingInviteCode()
        viewModel.redeemInvite(context, code) { }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        if (isShowingCreateSheet.value) {
            CreateSpaceSheet(
                onDismiss = { isShowingCreateSheet.value = false },
                isCreating = uiState.isCreating,
                onCreateSpace = { name, emoji, colorHex, description, template, enabledModules ->
                    viewModel.createSpace(context, name, emoji, colorHex, description, template, enabledModules)
                    if (!uiState.isCreating) {
                        isShowingCreateSheet.value = false
                    }
                }
            )
        }

        if (isShowingJoinSheet.value) {
            JoinSpaceSheet(
                onDismiss = { isShowingJoinSheet.value = false },
                isJoining = uiState.isJoining,
                onJoin = { code ->
                    viewModel.redeemInvite(context, code) {
                        isShowingJoinSheet.value = false
                    }
                }
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = onSearchSelected) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    SectionHeader(
                        title = uiState.greetingTitle,
                        subtitle = uiState.greetingSubtitle
                    )
                }
            }

            items(uiState.spaces) { space ->
                SpaceCard(space = space, onClick = { onSpaceSelected(space) })
            }

            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Button(
                    onClick = { isShowingCreateSheet.value = true },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "+ Create Space",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                OutlinedButton(
                    onClick = { isShowingJoinSheet.value = true },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Join with Invite Code",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
