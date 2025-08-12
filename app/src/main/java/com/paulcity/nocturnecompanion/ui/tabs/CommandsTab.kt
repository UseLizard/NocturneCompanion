package com.paulcity.nocturnecompanion.ui.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.paulcity.nocturnecompanion.ui.theme.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

@Composable
fun CommandsTab(
    lastCommand: String?,
    connectedDevicesCount: Int,
    onSendTestState: () -> Unit,
    onSendTestTimeSync: () -> Unit,
    onSendTestAlbumArt: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Test commands
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    "Test Commands",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = onSendTestState,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectedDevicesCount > 0
                ) {
                    Text("Send Test State Update")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = onSendTestTimeSync,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectedDevicesCount > 0
                ) {
                    Text("Send Time Sync")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = onSendTestAlbumArt,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectedDevicesCount > 0
                ) {
                    Text("Test Album Art Transfer")
                }
                
                if (connectedDevicesCount == 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Connect a device to enable test commands",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Last command
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    "Last Command Received",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                if (lastCommand != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = formatJson(lastCommand),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            ),
                            color = successColor()
                        )
                    }
                } else {
                    Text(
                        "No commands received yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

private fun formatJson(json: String): String {
    return try {
        val parser = JsonParser()
        val element = parser.parse(json)
        val gson = GsonBuilder().setPrettyPrinting().create()
        gson.toJson(element)
    } catch (e: Exception) {
        json // Return original if parsing fails
    }
}