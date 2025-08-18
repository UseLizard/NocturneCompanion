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
        // Unified Control Header
        PrimaryGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // Top row: Location and actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Location Selector
                    Box(modifier = Modifier.weight(1f)) {
                        FilledTonalButton(
                            onClick = { expanded = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
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
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
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
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
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
                                contentDescription = "Use current location",
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
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Bottom row: View mode selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box {
                        OutlinedButton(
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
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
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
        }

        Spacer(modifier = Modifier.height(12.dp))

        weatherResponse?.let {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    // Current Weather Summary
                    val currentTemp = if (viewMode == "Hourly" && it.hourly.temperature_2m.isNotEmpty()) {
                        it.hourly.temperature_2m[0].toInt()
                    } else if (it.daily.temperature_2m_max.isNotEmpty() && it.daily.temperature_2m_min.isNotEmpty()) {
                        val maxTemp = it.daily.temperature_2m_max[0].toInt()
                        val minTemp = it.daily.temperature_2m_min[0].toInt()
                        (maxTemp + minTemp) / 2
                    } else null
                    
                    val currentCondition = if (viewMode == "Hourly" && it.hourly.weathercode.isNotEmpty()) {
                        getWeatherCondition(it.hourly.weathercode[0])
                    } else if (it.daily.weathercode.isNotEmpty()) {
                        getWeatherCondition(it.daily.weathercode[0])
                    } else "Unknown"
                    
                    SurfaceGlassCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    currentTemp?.let { temp ->
                                        Text(
                                            text = "${temp}°F",
                                            style = MaterialTheme.typography.displayMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }
                                    Column {
                                        Text(
                                            text = currentCondition,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "${String.format("%.1f", it.latitude)}°, ${String.format("%.1f", it.longitude)}°",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            
                            if (isUsingCurrentLocation) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.LocationOn,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "Live Location",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (viewMode == "Hourly") {
                    
                    val todayString = selectedDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    val todayHourlyIndices = it.hourly.time.indices.filter { index ->
                        it.hourly.time[index].startsWith(todayString)
                    }
                    
                    items(todayHourlyIndices) { index ->
                        val time = it.hourly.time[index]
                        val temp = it.hourly.temperature_2m[index]
                        val weatherCode = it.hourly.weathercode[index]
                        
                        val tempF = temp
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
                            contentPadding = 14.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = formattedTime,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = getWeatherCondition(weatherCode),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                ) {
                                    Text(
                                        text = "${tempF.toInt()}°F",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    
                    items(7) { dayIndex ->
                        val time = it.daily.time.getOrNull(dayIndex)
                        val maxTemp = it.daily.temperature_2m_max.getOrNull(dayIndex)
                        val minTemp = it.daily.temperature_2m_min.getOrNull(dayIndex)
                        val weatherCode = it.daily.weathercode.getOrNull(dayIndex)
                        
                        if (time == null || maxTemp == null || minTemp == null || weatherCode == null) {
                            Log.e("WeatherTab", "Skipping daily item due to null data at index $dayIndex")
                            return@items
                        }
                        
                        val maxTempF = maxTemp.toInt()
                        val minTempF = minTemp.toInt()
                        
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
                            contentPadding = 14.dp
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
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = weatherCondition,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${minTempF}°F",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    ) {
                                        Text(
                                            text = "${maxTempF}°F",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
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
