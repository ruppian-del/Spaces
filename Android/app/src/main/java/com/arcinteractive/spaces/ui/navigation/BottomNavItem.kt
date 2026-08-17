package com.arcinteractive.spaces.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector

data class BottomNavItem(
    val destination: Destination,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)
