package com.arcinteractive.spaces.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arcinteractive.spaces.ui.auth.AuthViewModel
import com.arcinteractive.spaces.data.auth.PushTokenService
import com.arcinteractive.spaces.ui.screens.home.HomeViewModel
import com.arcinteractive.spaces.ui.screens.onboarding.OnboardingScreen
import com.arcinteractive.spaces.ui.screens.activity.ActivityScreen
import com.arcinteractive.spaces.ui.screens.home.HomeScreen
import com.arcinteractive.spaces.ui.screens.pings.PingsScreen
import com.arcinteractive.spaces.ui.screens.search.GlobalSearchScreen
import com.arcinteractive.spaces.ui.screens.space.EventsPlaceholderScreen
import com.arcinteractive.spaces.ui.screens.space.FilesPlaceholderScreen
import com.arcinteractive.spaces.ui.screens.space.GeneralPlaceholderScreen
import com.arcinteractive.spaces.ui.screens.space.MembersPlaceholderScreen
import com.arcinteractive.spaces.ui.screens.space.PhotosPlaceholderScreen
import com.arcinteractive.spaces.ui.screens.space.PollsPlaceholderScreen
import com.arcinteractive.spaces.ui.screens.space.SpaceSettingsPlaceholderScreen
import com.arcinteractive.spaces.ui.screens.space.SpaceDetailScreen
import com.arcinteractive.spaces.ui.screens.you.YouScreen
import kotlinx.coroutines.launch

@Composable
fun SpacesApp(
    appViewModel: AppViewModel = viewModel(),
    authViewModel: AuthViewModel = viewModel(),
    homeViewModel: HomeViewModel = viewModel()
) {
    val appUiState by appViewModel.uiState.collectAsState()
    val authUiState by authViewModel.uiState.collectAsState()
    val homeUiState by homeViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val pushTokenService = PushTokenService()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        authUiState.currentSession?.let { _ ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                pushTokenService.syncCurrentToken(context)
            }
        }
    }

    LaunchedEffect(Unit) {
        authViewModel.startObservingAuthState(context)
        authViewModel.restoreExistingSession(context)
    }

    LaunchedEffect(authUiState.currentSession?.uid) {
        val currentUserId = authUiState.currentSession?.uid
        if (currentUserId == null) {
            appViewModel.resetForSignedOutUser()
            homeViewModel.handleAuthState(context, null)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            pushTokenService.syncCurrentToken(context)
        }
    }

    if (authUiState.isResolvingUserState) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (authUiState.requiresProfileCreation) {
        OnboardingScreen(
            onComplete = {},
            authViewModel = authViewModel
        )
        return
    }

    if (authUiState.isAuthenticated) {
        // Fall through to the main app shell.
    } else if (!appUiState.hasCompletedOnboarding) {
        OnboardingScreen(
            onComplete = appViewModel::completeOnboarding,
            authViewModel = authViewModel
        )
        return
    }

    val navController = rememberNavController()
    val bottomNavItems = listOf(
        BottomNavItem(Destination.Home, "Home", Icons.Outlined.Home, Icons.Outlined.Home),
        BottomNavItem(Destination.Pings, "Pings", Icons.Outlined.Notifications, Icons.Outlined.Notifications),
        BottomNavItem(Destination.Activity, "Activity", Icons.AutoMirrored.Outlined.TrendingUp, Icons.AutoMirrored.Outlined.TrendingUp),
        BottomNavItem(Destination.You, "You", Icons.Outlined.Person, Icons.Outlined.Person)
    )

    val notificationRouteResolver = remember(homeUiState.spaces) {
        { request: PendingNotificationNavigation ->
            notificationRouteFor(request = request, spaces = homeUiState.spaces)
        }
    }

    LaunchedEffect(
        authUiState.isAuthenticated,
        homeUiState.isLoading,
        homeUiState.spaces,
        appUiState.pendingNotificationNavigation
    ) {
        val request = appUiState.pendingNotificationNavigation ?: return@LaunchedEffect
        if (!authUiState.isAuthenticated) return@LaunchedEffect

        val route = notificationRouteResolver(request)
        if (route != null) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
            appViewModel.clearPendingNotificationNavigation()
            return@LaunchedEffect
        }

        if (!request.spaceId.isNullOrBlank() && !homeUiState.isLoading) {
            navController.navigate(Destination.Activity.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
            appViewModel.clearPendingNotificationNavigation()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomNavItems.forEach { item ->
                    val selected = currentDestination?.hierarchy?.any { it.route == item.destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(item.destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = item.label
                            )
                        },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Destination.Home.route) {
                HomeScreen(
                    currentUserId = authUiState.currentSession?.uid,
                    onSpaceSelected = { space ->
                        navController.navigate(Destination.SpaceDetail.routeFor(space.id))
                    },
                    onSearchSelected = {
                        navController.navigate(Destination.GlobalSearch.route)
                    },
                    appViewModel = appViewModel,
                    viewModel = homeViewModel
                )
            }
            composable(Destination.Pings.route) {
                PingsScreen()
            }
            composable(Destination.GlobalSearch.route) {
                GlobalSearchScreen(
                    spaces = homeUiState.spaces,
                    onBack = { navController.popBackStack() },
                    onOpenSpace = { space ->
                        navController.navigate(Destination.SpaceDetail.routeFor(space.id))
                    },
                    onOpenSpaceMessages = { space ->
                        navController.navigate(Destination.GeneralPlaceholder.routeFor(space.id))
                    },
                    onOpenPing = { pingId ->
                        navController.navigate(Destination.PingConversation.routeFor(pingId))
                    }
                )
            }
            composable(
                route = Destination.PingConversation.route,
                arguments = listOf(navArgument("pingId") { defaultValue = "" })
            ) { backStackEntry ->
                val pingId = backStackEntry.arguments?.getString("pingId").orEmpty()
                PingsScreen(
                    initialPingId = pingId,
                    onExitConversation = { navController.popBackStack() }
                )
            }
            composable(Destination.Activity.route) {
                ActivityScreen(
                    spaces = homeUiState.spaces,
                    onNavigateToRoute = { route ->
                        navController.navigate(route)
                    }
                )
            }
            composable(Destination.You.route) {
                YouScreen(authViewModel = authViewModel)
            }
            composable(
                route = Destination.SpaceDetail.route,
                arguments = listOf(navArgument("spaceId") { defaultValue = "" })
            ) { backStackEntry ->
                val spaceId = backStackEntry.arguments?.getString("spaceId").orEmpty()
                homeUiState.spaces.firstOrNull { it.id == spaceId }?.let { space ->
                    SpaceDetailScreen(
                        space = space,
                        onBackPressed = { navController.popBackStack() },
                        onModuleSelected = { module ->
                            val route = when (module.id) {
                                "general" -> Destination.GeneralPlaceholder.routeFor(space.id)
                                "photos" -> Destination.PhotosPlaceholder.routeFor(space.id)
                                "files" -> Destination.FilesPlaceholder.routeFor(space.id)
                                "polls" -> Destination.PollsPlaceholder.routeFor(space.id)
                                "events" -> Destination.EventsPlaceholder.routeFor(space.id)
                                "members" -> Destination.MembersPlaceholder.routeFor(space.id)
                                else -> Destination.SpaceSettingsPlaceholder.routeFor(space.id)
                            }
                            navController.navigate(route)
                        }
                    )
                }
            }
            composable(
                route = Destination.GeneralPlaceholder.route,
                arguments = listOf(navArgument("spaceId") { defaultValue = "" })
            ) { backStackEntry ->
                val spaceId = backStackEntry.arguments?.getString("spaceId").orEmpty()
                homeUiState.spaces.firstOrNull { it.id == spaceId }?.let { space ->
                    GeneralPlaceholderScreen(space = space, onBackPressed = { navController.popBackStack() })
                }
            }
            composable(
                route = Destination.PhotosPlaceholder.route,
                arguments = listOf(navArgument("spaceId") { defaultValue = "" })
            ) { backStackEntry ->
                val spaceId = backStackEntry.arguments?.getString("spaceId").orEmpty()
                homeUiState.spaces.firstOrNull { it.id == spaceId }?.let { space ->
                    PhotosPlaceholderScreen(space = space, onBackPressed = { navController.popBackStack() })
                }
            }
            composable(
                route = Destination.FilesPlaceholder.route,
                arguments = listOf(navArgument("spaceId") { defaultValue = "" })
            ) { backStackEntry ->
                val spaceId = backStackEntry.arguments?.getString("spaceId").orEmpty()
                homeUiState.spaces.firstOrNull { it.id == spaceId }?.let { space ->
                    FilesPlaceholderScreen(space = space, onBackPressed = { navController.popBackStack() })
                }
            }
            composable(
                route = Destination.PollsPlaceholder.route,
                arguments = listOf(navArgument("spaceId") { defaultValue = "" })
            ) { backStackEntry ->
                val spaceId = backStackEntry.arguments?.getString("spaceId").orEmpty()
                homeUiState.spaces.firstOrNull { it.id == spaceId }?.let { space ->
                    PollsPlaceholderScreen(space = space, onBackPressed = { navController.popBackStack() })
                }
            }
            composable(
                route = Destination.EventsPlaceholder.route,
                arguments = listOf(navArgument("spaceId") { defaultValue = "" })
            ) { backStackEntry ->
                val spaceId = backStackEntry.arguments?.getString("spaceId").orEmpty()
                homeUiState.spaces.firstOrNull { it.id == spaceId }?.let { space ->
                    EventsPlaceholderScreen(space = space, onBackPressed = { navController.popBackStack() })
                }
            }
            composable(
                route = Destination.MembersPlaceholder.route,
                arguments = listOf(navArgument("spaceId") { defaultValue = "" })
            ) { backStackEntry ->
                val spaceId = backStackEntry.arguments?.getString("spaceId").orEmpty()
                homeUiState.spaces.firstOrNull { it.id == spaceId }?.let { space ->
                    MembersPlaceholderScreen(space = space, onBackPressed = { navController.popBackStack() })
                }
            }
            composable(
                route = Destination.SpaceSettingsPlaceholder.route,
                arguments = listOf(navArgument("spaceId") { defaultValue = "" })
            ) { backStackEntry ->
                val spaceId = backStackEntry.arguments?.getString("spaceId").orEmpty()
                homeUiState.spaces.firstOrNull { it.id == spaceId }?.let { space ->
                    SpaceSettingsPlaceholderScreen(space = space, onBackPressed = { navController.popBackStack() })
                }
            }
        }
    }
}

private fun notificationRouteFor(
    request: PendingNotificationNavigation,
    spaces: List<com.arcinteractive.spaces.data.model.Space>
): String? {
    val spaceId = request.spaceId?.trim().orEmpty()
    if (spaceId.isEmpty()) {
        return Destination.Activity.route
    }
    if (spaces.none { it.id == spaceId }) {
        return null
    }

    return when (request.targetType?.trim()?.lowercase()) {
        "general", "message" -> Destination.GeneralPlaceholder.routeFor(spaceId)
        "photos", "photo", "video" -> Destination.PhotosPlaceholder.routeFor(spaceId)
        "files", "file" -> Destination.FilesPlaceholder.routeFor(spaceId)
        "polls", "poll" -> Destination.PollsPlaceholder.routeFor(spaceId)
        "events", "event" -> Destination.EventsPlaceholder.routeFor(spaceId)
        "members", "member" -> Destination.MembersPlaceholder.routeFor(spaceId)
        "space", "", null -> Destination.SpaceDetail.routeFor(spaceId)
        else -> when (request.type?.trim()?.lowercase()) {
            "newmessage", "reply", "reaction", "ping" -> Destination.GeneralPlaceholder.routeFor(spaceId)
            "photoshared", "videoshared" -> Destination.PhotosPlaceholder.routeFor(spaceId)
            "fileuploaded" -> Destination.FilesPlaceholder.routeFor(spaceId)
            "pollcreated", "pollclosed" -> Destination.PollsPlaceholder.routeFor(spaceId)
            "eventcreated", "eventupdated", "eventreminder" -> Destination.EventsPlaceholder.routeFor(spaceId)
            "memberjoined" -> Destination.MembersPlaceholder.routeFor(spaceId)
            else -> Destination.SpaceDetail.routeFor(spaceId)
        }
    }
}
