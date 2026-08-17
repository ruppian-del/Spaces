package com.arcinteractive.spaces.ui.screens.space

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceModule
import com.arcinteractive.spaces.data.organization.OrganizationService
import com.arcinteractive.spaces.ui.components.ModuleCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceDetailScreen(
    space: Space,
    onBackPressed: () -> Unit,
    onModuleSelected: (SpaceModule) -> Unit
) {
    val context = LocalContext.current
    var entitledModuleIds by remember(space.organizationId) { mutableStateOf<Set<String>?>(if (space.organizationId == null) null else emptySet()) }
    var entitlementError by remember(space.organizationId) { mutableStateOf<String?>(null) }
    LaunchedEffect(space.organizationId) {
        val organizationId = space.organizationId ?: return@LaunchedEffect
        runCatching { OrganizationService().effectiveEntitlements(context, organizationId).enabledModuleIds }
            .onSuccess { entitledModuleIds = it; entitlementError = null }
            .onFailure { entitledModuleIds = emptySet(); entitlementError = it.localizedMessage }
    }
    val visibleModules = if (space.organizationId == null) space.modules else space.modules.filter { it.id in entitledModuleIds.orEmpty() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(space.name) },
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = Color(android.graphics.Color.parseColor(space.colorHex)).copy(alpha = 0.18f)
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = space.emoji, fontSize = 30.sp)
                        Text(
                            text = space.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = space.subtitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Modules",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            items(visibleModules) { module ->
                ModuleCard(
                    module = module,
                    onClick = { onModuleSelected(module) }
                )
            }
            entitlementError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
    }
}
