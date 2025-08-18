package com.paulcity.nocturnecompanion.ui.tabs

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.paulcity.nocturnecompanion.services.NocturneServiceBLE
import com.paulcity.nocturnecompanion.ui.components.PrimaryGlassCard
import com.paulcity.nocturnecompanion.ui.components.SurfaceGlassCard
import kotlinx.coroutines.launch

@Composable
fun ConnectionSettingsTab() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Album art transfer settings
    var imageFormat by remember { mutableStateOf("JPEG") }
    var compressionQuality by remember { mutableStateOf(85) }
    var imageSize by remember { mutableStateOf(300) }
    var chunkSize by remember { mutableStateOf(512) }
    var chunkDelayMs by remember { mutableStateOf(5) }
    var useBinaryProtocol by remember { mutableStateOf(true) }
    
    // Load saved settings
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("AlbumArtSettings", Context.MODE_PRIVATE)
        imageFormat = prefs.getString("imageFormat", "JPEG") ?: "JPEG"
        compressionQuality = prefs.getInt("compressionQuality", 85)
        imageSize = prefs.getInt("imageSize", 300)
        chunkSize = prefs.getInt("chunkSize", 512)
        chunkDelayMs = prefs.getInt("chunkDelayMs", 5)
        useBinaryProtocol = prefs.getBoolean("useBinaryProtocol", true)
    }
    
    fun saveSettings() {
        val prefs = context.getSharedPreferences("AlbumArtSettings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("imageFormat", imageFormat)
            putInt("compressionQuality", compressionQuality)
            putInt("imageSize", imageSize)
            putInt("chunkSize", chunkSize)
            putInt("chunkDelayMs", chunkDelayMs)
            putBoolean("useBinaryProtocol", useBinaryProtocol)
            apply()
        }
        
        // Send to service
        val intent = Intent(context, NocturneServiceBLE::class.java).apply {
            action = NocturneServiceBLE.ACTION_UPDATE_ALBUM_ART_SETTINGS
            putExtra("imageFormat", imageFormat)
            putExtra("compressionQuality", compressionQuality)
            putExtra("imageSize", imageSize)
            putExtra("chunkSize", chunkSize)
            putExtra("chunkDelayMs", chunkDelayMs)
            putExtra("useBinaryProtocol", useBinaryProtocol)
        }
        context.startService(intent)
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Image Format Selection
        PrimaryGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
                Text(
                    "Image Format",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = imageFormat == "JPEG",
                        onClick = { 
                            imageFormat = "JPEG"
                            saveSettings()
                        },
                        label = { Text("JPEG") },
                        modifier = Modifier.weight(1f)
                    )
                    
                    FilterChip(
                        selected = imageFormat == "WEBP",
                        onClick = { 
                            imageFormat = "WEBP"
                            saveSettings()
                        },
                        label = { Text("WebP") },
                        modifier = Modifier.weight(1f)
                    )
                    
                    FilterChip(
                        selected = imageFormat == "PNG",
                        onClick = { 
                            imageFormat = "PNG"
                            saveSettings()
                        },
                        label = { Text("PNG") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Compression Settings
        PrimaryGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
                Text(
                    "Compression & Size",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Compression Quality (only for JPEG/WebP)
                if (imageFormat != "PNG") {
                    Text(
                        "Compression Quality: $compressionQuality%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Slider(
                        value = compressionQuality.toFloat(),
                        onValueChange = { compressionQuality = it.toInt() },
                        valueRange = 10f..100f,
                        onValueChangeFinished = { saveSettings() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Image Size
                Text(
                    "Image Size: ${imageSize}x${imageSize}px",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Slider(
                    value = imageSize.toFloat(),
                    onValueChange = { imageSize = it.toInt() },
                    valueRange = 100f..500f,
                    steps = 7,
                    onValueChangeFinished = { saveSettings() },
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Estimated size
                val estimatedSize = when(imageFormat) {
                    "JPEG" -> (imageSize * imageSize * 3 * compressionQuality / 100 / 8).toInt()
                    "WEBP" -> (imageSize * imageSize * 3 * compressionQuality / 100 / 10).toInt()
                    else -> imageSize * imageSize * 3
                }
                
                Text(
                    "Estimated size: ${estimatedSize / 1024} KB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Transfer Settings
        PrimaryGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
                Text(
                    "Transfer Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Binary Protocol Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Use Binary Protocol",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Switch(
                        checked = useBinaryProtocol,
                        onCheckedChange = { 
                            useBinaryProtocol = it
                            saveSettings()
                        }
                    )
                }
                
                Text(
                    if (useBinaryProtocol) "Faster, more efficient transfers" else "JSON-based transfers (slower)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Chunk Size
                Text(
                    "Chunk Size: $chunkSize bytes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Slider(
                    value = chunkSize.toFloat(),
                    onValueChange = { chunkSize = it.toInt() },
                    valueRange = 128f..2048f,
                    steps = 14,
                    onValueChangeFinished = { saveSettings() },
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Chunk Delay
                Text(
                    "Chunk Delay: $chunkDelayMs ms",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Slider(
                    value = chunkDelayMs.toFloat(),
                    onValueChange = { chunkDelayMs = it.toInt() },
                    valueRange = 0f..50f,
                    steps = 9,
                    onValueChangeFinished = { saveSettings() },
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Transfer time estimate
                val estSize = when(imageFormat) {
                    "JPEG" -> (imageSize * imageSize * 3 * compressionQuality / 100 / 8).toInt()
                    "WEBP" -> (imageSize * imageSize * 3 * compressionQuality / 100 / 10).toInt()
                    else -> imageSize * imageSize * 3
                }
                val totalChunks = estSize / chunkSize + 1
                val transferTime = totalChunks * (chunkDelayMs + 10) // 10ms for actual transfer
                
                Text(
                    "Estimated transfer time: ${transferTime / 1000.0}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { 
                    // Reset to defaults
                    imageFormat = "JPEG"
                    compressionQuality = 85
                    imageSize = 300
                    chunkSize = 512
                    chunkDelayMs = 5
                    useBinaryProtocol = true
                    saveSettings()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset Defaults", color = MaterialTheme.colorScheme.onPrimary)
            }
            
            Button(
                onClick = { 
                    // Test with current settings
                    val intent = Intent(context, NocturneServiceBLE::class.java).apply {
                        action = NocturneServiceBLE.ACTION_TEST_ALBUM_ART
                    }
                    context.startService(intent)
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Test Transfer", color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Info Card
        SurfaceGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        "Tips for Best Performance",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "• Use JPEG for best compression\n" +
                        "• Keep chunk size around 512 bytes\n" +
                        "• Lower quality to 70-80% for faster transfers\n" +
                        "• Enable binary protocol for 2x speed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}