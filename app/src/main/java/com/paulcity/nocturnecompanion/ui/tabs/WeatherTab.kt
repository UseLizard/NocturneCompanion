package com.paulcity.nocturnecompanion.ui.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.util.Log
import com.paulcity.nocturnecompanion.ui.UnifiedMainViewModel
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
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(
                        text = if (isUsingCurrentLocation && currentLocationName != null) {
                            currentLocationName!!
                        } else {
                            selectedCity
                        }
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.getCurrentLocation() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isUsingCurrentLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        Icons.Default.LocationOn, 
                        contentDescription = "Get current location",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("My Location", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = {
                    if (isUsingCurrentLocation) {
                        viewModel.currentLocation.value?.let {
                            viewModel.fetchWeather(it.latitude, it.longitude)
                        }
                    } else {
                        // Refresh based on selected city
                        viewModel.onCitySelected(selectedCity)
                    }
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh weather")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Box {
                OutlinedButton(onClick = { viewModeExpanded = true }) {
                    Text("View: $viewMode")
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

        Spacer(modifier = Modifier.height(16.dp))

        weatherResponse?.let {
            LazyColumn {
                item {
                    val displayLocation = if (isUsingCurrentLocation && currentLocationName != null) {
                        currentLocationName!!
                    } else {
                        selectedCity
                    }
                    Text(
                        text = "${viewMode} Forecast for $displayLocation",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Lat: ${String.format("%.2f", it.latitude)}, Lon: ${String.format("%.2f", it.longitude)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isUsingCurrentLocation) {
                        Text(
                            text = "📍 Using current location",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                if (viewMode == "Hourly") {
                    item {
                        Text(
                            text = "Today - ${selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
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
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = formattedTime,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = getWeatherCondition(weatherCode),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${tempF.toInt()}°F",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            text = "7-Day Forecast",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
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
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = formattedDate,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(2f)
                                    )
                                    Text(
                                        text = weatherCondition,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "Morning",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "${avgTempF}°F",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "Peak",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "${maxTempF}°F",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "Night",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "${minTempF}°F",
                                            style = MaterialTheme.typography.bodyMedium
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
                CircularProgressIndicator()
            }
        }
    }
}
