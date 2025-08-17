package com.paulcity.nocturnecompanion.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class TabItem(
    val id: Int,
    val title: String
)

data class TabGroup(
    val title: String,
    val tabs: List<TabItem>
)

@Composable
fun SidebarNavigation(
    selectedTabId: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    val tabGroups = listOf(
        TabGroup(
            title = "System",
            tabs = listOf(
                TabItem(0, "Status"),
                TabItem(10, "Settings")
            )
        ),
        TabGroup(
            title = "Connectivity",
            tabs = listOf(
                TabItem(1, "Devices"),
                TabItem(2, "Connection"),
                TabItem(3, "Transfer")
            )
        ),
        TabGroup(
            title = "Media",
            tabs = listOf(
                TabItem(4, "Media"),
                TabItem(7, "Audio"),
                TabItem(8, "Podcasts"),
                TabItem(9, "Gradient")
            )
        ),
        TabGroup(
            title = "Development",
            tabs = listOf(
                TabItem(5, "Commands"),
                TabItem(6, "Logs")
            )
        ),
        TabGroup(
            title = "Information",
            tabs = listOf(
                TabItem(11, "Weather")
            )
        )
    )

    // Animated width based on expanded state
    val targetWidth = if (isExpanded) 112.dp else 48.dp
    
    Card(
        modifier = modifier
            .fillMaxHeight()
            .width(targetWidth)
            .animateContentSize(
                animationSpec = tween(durationMillis = 300)
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Toggle button
            DrawerToggleButton(
                isExpanded = isExpanded,
                onToggle = { isExpanded = !isExpanded }
            )
            
            // Content that shows only when expanded
            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Navigation",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    tabGroups.forEach { group ->
                        TabGroupSection(
                            group = group,
                            selectedTabId = selectedTabId,
                            onTabSelected = onTabSelected
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerToggleButton(
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "button_rotation"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 2.dp
        ) {
            Box(
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(rotation)
                )
            }
        }
    }
}

@Composable
private fun TabGroupSection(
    group: TabGroup,
    selectedTabId: Int,
    onTabSelected: (Int) -> Unit
) {
    Column {
        // Group header
        Text(
            text = group.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Tab items
        group.tabs.forEach { tab ->
            SidebarTabItem(
                tab = tab,
                isSelected = selectedTabId == tab.id,
                onTabSelected = onTabSelected
            )
        }
    }
}

@Composable
private fun SidebarTabItem(
    tab: TabItem,
    isSelected: Boolean,
    onTabSelected: (Int) -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    
    val textColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable { onTabSelected(tab.id) }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = tab.title,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
        )
    }
}