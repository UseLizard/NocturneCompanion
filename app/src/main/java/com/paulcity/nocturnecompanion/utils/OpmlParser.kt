package com.paulcity.nocturnecompanion.utils

import android.util.Log
import com.paulcity.nocturnecompanion.data.Podcast
import com.paulcity.nocturnecompanion.data.PodcastCollection
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

class OpmlParser {
    companion object {
        private const val TAG = "OpmlParser"
        
        fun parse(inputStream: InputStream): PodcastCollection? {
            return try {
                val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                val document = documentBuilder.parse(inputStream)
                document.documentElement.normalize()
                
                val headElement = document.getElementsByTagName("head").item(0) as? Element
                val title = headElement?.getElementsByTagName("title")?.item(0)?.textContent ?: "Imported Podcasts"
                val dateCreated = headElement?.getElementsByTagName("dateCreated")?.item(0)?.textContent ?: ""
                val dateModified = headElement?.getElementsByTagName("dateModified")?.item(0)?.textContent ?: ""
                
                val podcasts = mutableListOf<Podcast>()
                val outlineNodes = document.getElementsByTagName("outline")
                
                for (i in 0 until outlineNodes.length) {
                    val outline = outlineNodes.item(i) as Element
                    if (outline.hasAttribute("xmlUrl")) {
                        val podcastTitle = outline.getAttribute("text") ?: ""
                        val feedUrl = outline.getAttribute("xmlUrl") ?: ""
                        val websiteUrl = outline.getAttribute("htmlUrl").takeIf { it.isNotEmpty() }
                        val imageUrl = outline.getAttribute("imageUrl").takeIf { it.isNotEmpty() }
                        
                        if (podcastTitle.isNotEmpty() && feedUrl.isNotEmpty()) {
                            podcasts.add(
                                Podcast(
                                    title = unescapeXml(podcastTitle),
                                    feedUrl = feedUrl,
                                    websiteUrl = websiteUrl,
                                    imageUrl = imageUrl
                                )
                            )
                        }
                    }
                }
                
                Log.d(TAG, "Parsed ${podcasts.size} podcasts from OPML")
                
                PodcastCollection(
                    title = title,
                    dateCreated = dateCreated,
                    dateModified = dateModified,
                    podcasts = podcasts
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing OPML", e)
                null
            }
        }
        
        private fun unescapeXml(text: String): String {
            return text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
        }
    }
}