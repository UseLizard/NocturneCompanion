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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.paulcity.nocturnecompanion.ui.components.ModernTabItem
import androidx.compose.ui.unit.dp



@Composable
fun ModernSidebarNavigation(
    selectedTabId: Int,
    onTabSelected: (Int) -> Unit,
    tabItems: List<ModernTabItem>,
    modifier: Modifier = Modifier,
    isHorizontal: Boolean = false
) {

    if (isHorizontal) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 300)),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tab Items
            tabItems.forEach { item ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onTabSelected(item.id) }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (selectedTabId == item.id) item.selectedIcon else item.icon,
                            contentDescription = item.title,
                            modifier = Modifier.size(24.dp),
                            tint = if (selectedTabId == item.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (selectedTabId == item.id) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Divider(
                                modifier = Modifier.width(24.dp),
                                color = MaterialTheme.colorScheme.primary,
                                thickness = 2.dp
                            )
                            
                        }
                    }
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
                    modifier = Modifier.padding(vertical = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = "Nocturne",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Nocturne",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
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
