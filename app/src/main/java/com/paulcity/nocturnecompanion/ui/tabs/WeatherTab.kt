package com.paulcity.nocturnecompanion.ui.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.util.Log
import com.paulcity.nocturnecompanion.ui.UnifiedMainViewModel
import com.paulcity.nocturnecompanion.ui.components.PrimaryGlassCard
import com.paulcity.nocturnecompanion.ui.components.SurfaceGlassCard
import com.paulcity.nocturnecompanion.ui.components.MinimalGlassCard
import java.text.SimpleDateFormat
import java.util.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun WeatherTab(viewModel: UnifiedMainViewModel) {
    val weatherResponse by viewModel.weatherResponse
    val cities by remember { mutableStateOf(viewModel.cities) }
    var selectedCity by viewModel.selectedCity
    var expanded by remember { mutableStateOf(false) }
    val isUsingCurrentLocation by viewModel.isUsingCurrentLocation
    val currentLocationName by viewModel.currentLocationName
    var viewMode by remember { mutableStateOf("Hourly") }
    var viewModeExpanded by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    
    fun getWeatherCondition(weatherCode: Int): String {
        return when (weatherCode) {
            0 -> "Clear"
            1 -> "Partly Cloudy"
            2, 3 -> "Cloudy"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing Drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing Rain"
            71, 73, 75, 77 -> "Snow"
            80, 81, 82 -> "Rain Showers"
            85, 86 -> "Snow Showers"
            95 -> "Thunderstorm"
            96, 99 -> "Heavy Thunderstorm"
            else -> "Unknown"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Header Controls Card
        PrimaryGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Location Selector
                Box {
                    FilledTonalButton(
                        onClick = { expanded = true },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isUsingCurrentLocation && currentLocationName != null) {
                                currentLocationName!!
                            } else {
                                selectedCity
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        cities.forEach { city ->
                            DropdownMenuItem(
                                text = { Text(city) },
                                onClick = {
                                    selectedCity = city
                                    expanded = false
                                    viewModel.onCitySelected(city)
                                }
                            )
                        }
                    }
                }
                
                // Action Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.getCurrentLocation() },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (isUsingCurrentLocation) 
                                MaterialTheme.colorScheme.primaryContainer 
                            else 
                                MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    
                    FilledTonalButton(
                        onClick = {
                            if (isUsingCurrentLocation) {
                                viewModel.currentLocation.value?.let {
                                    viewModel.fetchWeather(it.latitude, it.longitude)
                                }
                            } else {
                                viewModel.onCitySelected(selectedCity)
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // View Mode Selector
        SurfaceGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Box {
                    FilledTonalButton(
                        onClick = { viewModeExpanded = true },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            if (viewMode == "Hourly") Icons.Default.WbSunny else Icons.Default.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "$viewMode Forecast",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = viewModeExpanded,
                        onDismissRequest = { viewModeExpanded = false }
                    ) {
                        listOf("Hourly", "Daily").forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode) },
                                onClick = {
                                    viewMode = mode
                                    viewModeExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        weatherResponse?.let {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    // Location Info Header
                    val displayLocation = if (isUsingCurrentLocation && currentLocationName != null) {
                        currentLocationName!!
                    } else {
                        selectedCity
                    }
                    
                    PrimaryGlassCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text(
                                text = displayLocation,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${String.format("%.2f", it.latitude)}, ${String.format("%.2f", it.longitude)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (isUsingCurrentLocation) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.LocationOn,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                "Current Location",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (viewMode == "Hourly") {
                    item {
                        SurfaceGlassCard(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.WbSunny,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Today - ${selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    
                    val todayString = selectedDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    val todayHourlyIndices = it.hourly.time.indices.filter { index ->
                        it.hourly.time[index].startsWith(todayString)
                    }
                    
                    items(todayHourlyIndices) { index ->
                        val time = it.hourly.time[index]
                        val temp = it.hourly.temperature_2m[index]
                        val weatherCode = it.hourly.weathercode[index]
                        
                        val tempF = (temp * 9/5) + 32
                        val formattedTime = try {
                            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
                            val outputFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                            val date = inputFormat.parse(time)
                            date?.let { outputFormat.format(it) } ?: time
                        } catch (e: Exception) {
                            time
                        }
                        
                        MinimalGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = 16.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = formattedTime,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = getWeatherCondition(weatherCode),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1.2f)
                                )
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                ) {
                                    Text(
                                        text = "${tempF.toInt()}°F",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item {
                        SurfaceGlassCard(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Cloud,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "7-Day Forecast",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    
                    items(7) { dayIndex ->
                        val time = it.daily.time.getOrNull(dayIndex)
                        val maxTemp = it.daily.temperature_2m_max.getOrNull(dayIndex)
                        val minTemp = it.daily.temperature_2m_min.getOrNull(dayIndex)
                        val weatherCode = it.daily.weathercode.getOrNull(dayIndex)
                        
                        if (time == null || maxTemp == null || minTemp == null || weatherCode == null) {
                            Log.e("WeatherTab", "Skipping daily item due to null data at index $dayIndex")
                            return@items
                        }
                        
                        val maxTempF = ((maxTemp * 9/5) + 32).toInt()
                        val minTempF = ((minTemp * 9/5) + 32).toInt()
                        val avgTempF = (maxTempF + minTempF) / 2
                        
                        val formattedDate = try {
                            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            val outputFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
                            val date = inputFormat.parse(time)
                            outputFormat.format(date ?: Date())
                        } catch (e: Exception) {
                            time
                        }
                        
                        val weatherCondition = getWeatherCondition(weatherCode)
                        
                        MinimalGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = 16.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier.weight(2f)
                                ) {
                                    Text(
                                        text = formattedDate,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = weatherCondition,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "Low",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "${minTempF}°",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "High",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        ) {
                                            Text(
                                                text = "${maxTempF}°F",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } ?: run {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                PrimaryGlassCard {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Loading weather data...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
