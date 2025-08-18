package com.paulcity.nocturnecompanion.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class ModernTabItem(
    val id: Int,
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon
)

@Composable
fun ModernSidebarNavigation(
    selectedTabId: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isHorizontal: Boolean = false
) {
    val tabItems = listOf(
        ModernTabItem(1, "Devices", Icons.Outlined.DevicesOther, Icons.Filled.DevicesOther),
        ModernTabItem(2, "Connection", Icons.Outlined.Link, Icons.Filled.Link),
        ModernTabItem(3, "Transfer", Icons.Outlined.CloudSync, Icons.Filled.CloudSync),
        ModernTabItem(4, "Media", Icons.Outlined.PlayArrow, Icons.Filled.PlayArrow),
        ModernTabItem(7, "Audio", Icons.Outlined.VolumeUp, Icons.Filled.VolumeUp),
        ModernTabItem(8, "Podcasts", Icons.Outlined.Podcasts, Icons.Filled.Podcasts),
        ModernTabItem(5, "Commands", Icons.Outlined.Terminal, Icons.Filled.Terminal),
        ModernTabItem(10, "Weather", Icons.Outlined.Cloud, Icons.Filled.Cloud)
    )

    if (isHorizontal) {
        // Horizontal navigation for portrait mode - use NavigationBar with limited items
        // If more than 5 items, show scrollable row of custom navigation items
        if (tabItems.size <= 5) {
            NavigationBar(
                modifier = modifier
                    .fillMaxWidth()
                    .animateContentSize(animationSpec = tween(durationMillis = 300)),
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f)
            ) {
                tabItems.forEach { item ->
                    NavigationBarItem(
                        selected = selectedTabId == item.id,
                        onClick = { onTabSelected(item.id) },
                        icon = { 
                            Icon(
                                imageVector = if (selectedTabId == item.id) item.selectedIcon else item.icon,
                                contentDescription = item.title,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = { 
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            ) 
                        }
                    )
                }
            }
        } else {
            // Scrollable horizontal navigation for many items
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .animateContentSize(animationSpec = tween(durationMillis = 300)),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    tabItems.forEach { item ->
                        FilterChip(
                            selected = selectedTabId == item.id,
                            onClick = { onTabSelected(item.id) },
                            label = { 
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.labelSmall
                                ) 
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (selectedTabId == item.id) item.selectedIcon else item.icon,
                                    contentDescription = item.title,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }
        }
    } else {
        // Vertical navigation rail for landscape mode
        NavigationRail(
            modifier = modifier
                .fillMaxHeight()
                .animateContentSize(animationSpec = tween(durationMillis = 300)),
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
            header = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .clickable(
                            onClick = { onTabSelected(9) } // Navigate to combined Status/Settings tab
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = "Nocturne - Tap for Status & Settings",
                        modifier = Modifier.size(32.dp),
                        tint = if (selectedTabId == 9) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Nocturne",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selectedTabId == 9) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Add padding at top to allow for centered content when scrolling
                Spacer(modifier = Modifier.height(8.dp))
                
                tabItems.forEach { item ->
                    NavigationRailItem(
                        selected = selectedTabId == item.id,
                        onClick = { onTabSelected(item.id) },
                        icon = { 
                            Icon(
                                imageVector = if (selectedTabId == item.id) item.selectedIcon else item.icon,
                                contentDescription = item.title,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = { 
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.labelSmall
                            ) 
                        },
                        alwaysShowLabel = false
                    )
                }
                
                // Add padding at bottom to allow for centered content when scrolling
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
