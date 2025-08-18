package com.paulcity.nocturnecompanion.utils

import android.util.Log
import com.paulcity.nocturnecompanion.data.PodcastEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

object RssParser {
    private const val TAG = "RssParser"
    
    suspend fun fetchEpisodes(rssUrl: String): List<PodcastEpisode> = withContext(Dispatchers.IO) {
        try {
            val url = URL(rssUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "NocturneCompanion/1.0")
            
            connection.inputStream.use { inputStream ->
                parseRss(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching RSS feed: $rssUrl", e)
            emptyList()
        }
    }
    
    private fun parseRss(inputStream: InputStream): List<PodcastEpisode> {
        val episodes = mutableListOf<PodcastEpisode>()
        
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(inputStream, null)
            
            var eventType = parser.eventType
            var currentEpisode: MutableMap<String, String>? = null
            var currentTag = ""
            
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.lowercase()
                        when (currentTag) {
                            "item" -> {
                                currentEpisode = mutableMapOf()
                            }
                            "enclosure" -> {
                                currentEpisode?.let { episode ->
                                    val url = parser.getAttributeValue(null, "url")
                                    val type = parser.getAttributeValue(null, "type")
                                    if (url != null && type?.startsWith("audio") == true) {
                                        episode["audioUrl"] = url
                                    }
                                }
                            }
                        }
                    }
                    
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim()
                        if (!text.isNullOrEmpty() && currentEpisode != null) {
                            when (currentTag) {
                                "title" -> currentEpisode["title"] = text
                                "description" -> currentEpisode["description"] = text
                                "pubdate" -> currentEpisode["publishDate"] = text
                                "guid" -> currentEpisode["guid"] = text
                                "itunes:duration" -> currentEpisode["duration"] = text
                                "itunes:image" -> {
                                    // Handle iTunes image tags
                                }
                            }
                        }
                    }
                    
                    XmlPullParser.END_TAG -> {
                        if (parser.name.lowercase() == "item" && currentEpisode != null) {
                            val title = currentEpisode["title"] ?: "Untitled Episode"
                            val description = currentEpisode["description"]
                            val audioUrl = currentEpisode["audioUrl"]
                            val publishDate = currentEpisode["publishDate"]
                            val duration = currentEpisode["duration"]
                            val guid = currentEpisode["guid"]
                            
                            episodes.add(
                                PodcastEpisode(
                                    title = title,
                                    description = description,
                                    audioUrl = audioUrl,
                                    publishDate = formatDate(publishDate),
                                    duration = formatDuration(duration),
                                    guid = guid
                                )
                            )
                            currentEpisode = null
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing RSS", e)
        }
        
        return episodes
    }
    
    private fun formatDate(dateString: String?): String? {
        if (dateString.isNullOrEmpty()) return null
        
        return try {
            // Try common RSS date formats
            val formats = listOf(
                "EEE, dd MMM yyyy HH:mm:ss Z",
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                "yyyy-MM-dd'T'HH:mm:ssZ"
            )
            
            for (format in formats) {
                try {
                    val inputFormat = SimpleDateFormat(format, Locale.ENGLISH)
                    val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                    val date = inputFormat.parse(dateString)
                    return date?.let { outputFormat.format(it) }
                } catch (e: Exception) {
                    // Try next format
                }
            }
            
            // If no format works, return the original string
            dateString
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse date: $dateString", e)
            dateString
        }
    }
    
    private fun formatDuration(duration: String?): String? {
        if (duration.isNullOrEmpty()) return null
        
        return try {
            // Handle different duration formats
            when {
                duration.contains(":") -> {
                    // Already in HH:MM:SS or MM:SS format
                    duration
                }
                duration.all { it.isDigit() } -> {
                    // Seconds only, convert to MM:SS or HH:MM:SS
                    val totalSeconds = duration.toInt()
                    val hours = totalSeconds / 3600
                    val minutes = (totalSeconds % 3600) / 60
                    val seconds = totalSeconds % 60
                    
                    if (hours > 0) {
                        String.format("%d:%02d:%02d", hours, minutes, seconds)
                    } else {
                        String.format("%d:%02d", minutes, seconds)
                    }
                }
                else -> duration
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse duration: $duration", e)
            duration
        }
    }
}