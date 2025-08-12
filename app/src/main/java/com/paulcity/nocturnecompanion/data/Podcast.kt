package com.paulcity.nocturnecompanion.data

import kotlinx.serialization.Serializable

@Serializable
data class Podcast(
    val title: String,
    val feedUrl: String,
    val websiteUrl: String? = null,
    val imageUrl: String? = null,
    val importedAt: Long = System.currentTimeMillis()
)

@Serializable
data class PodcastCollection(
    val title: String,
    val dateCreated: String,
    val dateModified: String,
    val podcasts: List<Podcast>,
    val importedAt: Long = System.currentTimeMillis()
)