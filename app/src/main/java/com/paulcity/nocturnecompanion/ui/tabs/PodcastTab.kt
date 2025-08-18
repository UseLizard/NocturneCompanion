package com.paulcity.nocturnecompanion.ui.tabs

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paulcity.nocturnecompanion.data.Podcast
import com.paulcity.nocturnecompanion.data.PodcastCollection
import com.paulcity.nocturnecompanion.data.PodcastEpisode
import com.paulcity.nocturnecompanion.utils.OpmlParser
import com.paulcity.nocturnecompanion.utils.PodcastStorageManager
import com.paulcity.nocturnecompanion.utils.RssParser
import com.paulcity.nocturnecompanion.ui.components.MusicNote
import com.paulcity.nocturnecompanion.ui.components.Link
import com.paulcity.nocturnecompanion.ui.components.Description
import com.paulcity.nocturnecompanion.ui.components.GlassCard
import com.paulcity.nocturnecompanion.ui.components.GlassType
import com.paulcity.nocturnecompanion.ui.components.MinimalGlassCard
import com.paulcity.nocturnecompanion.ui.components.PrimaryGlassCard
import com.paulcity.nocturnecompanion.ui.components.SurfaceGlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PodcastTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storageManager = remember { PodcastStorageManager(context) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    
    var podcastCollection by remember { mutableStateOf<PodcastCollection?>(null) }
    var selectedPodcast by remember { mutableStateOf<Podcast?>(null) }
    var podcastEpisodes by remember { mutableStateOf<List<PodcastEpisode>?>(null) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            uri?.let {
                scope.launch {
                    isLoading = true
                    showError = false
                    try {
                        val collection = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                OpmlParser.parse(inputStream)
                            }
                        }
                        
                        if (collection != null && collection.podcasts.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                storageManager.savePodcastCollection(collection)
                            }
                            podcastCollection = collection
                            Log.d("PodcastTab", "Successfully imported ${collection.podcasts.size} podcasts")
                        } else {
                            errorMessage = "No podcasts found in the OPML file"
                            showError = true
                        }
                    } catch (e: Exception) {
                        Log.e("PodcastTab", "Error importing OPML", e)
                        errorMessage = "Error importing OPML: ${e.message}"
                        showError = true
                    } finally {
                        isLoading = false
                    }
                }
            }
        }
    )
    
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            podcastCollection = storageManager.loadPodcastCollection()
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Main content area
        if (selectedPodcast != null) {
            // Episode detail view
            PodcastDetailView(
                podcast = selectedPodcast!!,
                episodes = podcastEpisodes,
                isLoadingEpisodes = isLoadingEpisodes,
                isLandscape = isLandscape,
                onBack = { 
                    selectedPodcast = null 
                    podcastEpisodes = null
                },
                onLoadEpisodes = {
                    scope.launch {
                        isLoadingEpisodes = true
                        try {
                            val episodes = RssParser.fetchEpisodes(selectedPodcast!!.feedUrl)
                            podcastEpisodes = episodes
                        } catch (e: Exception) {
                            Log.e("PodcastTab", "Error loading episodes", e)
                        } finally {
                            isLoadingEpisodes = false
                        }
                    }
                }
            )
        } else {
            // Podcast grid view
            Column(modifier = Modifier.fillMaxSize()) {
                // Search bar (only if we have podcasts)
                podcastCollection?.let { collection ->
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search podcasts") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        singleLine = true
                    )
                }
        
                // Error display
                if (showError) {
                    SurfaceGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { showError = false },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
        
                // Main content
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    podcastCollection?.let { collection ->
                        val filteredPodcasts = if (searchQuery.isBlank()) {
                            collection.podcasts
                        } else {
                            collection.podcasts.filter {
                                it.title.contains(searchQuery, ignoreCase = true)
                            }
                        }
                        
                        PodcastGrid(
                            podcasts = filteredPodcasts,
                            isLandscape = isLandscape,
                            onPodcastClick = { podcast ->
                                selectedPodcast = podcast
                                // Auto-load episodes when podcast is selected
                                scope.launch {
                                    isLoadingEpisodes = true
                                    try {
                                        val episodes = RssParser.fetchEpisodes(podcast.feedUrl)
                                        podcastEpisodes = episodes
                                    } catch (e: Exception) {
                                        Log.e("PodcastTab", "Error loading episodes", e)
                                    } finally {
                                        isLoadingEpisodes = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    } ?: EmptyPodcastsView()
                }
            }
        }
        
        // Minimal OPML import at bottom
        if (selectedPodcast == null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (podcastCollection != null) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        storageManager.clearPodcasts()
                                    }
                                    podcastCollection = null
                                    searchQuery = ""
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Clear Library",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    
                    FilledTonalIconButton(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Import OPML",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PodcastGrid(
    podcasts: List<Podcast>,
    isLandscape: Boolean,
    onPodcastClick: (Podcast) -> Unit,
    modifier: Modifier = Modifier
) {
    val columns = if (isLandscape) 4 else 2
    
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        items(podcasts) { podcast ->
            PodcastCard(
                podcast = podcast,
                onClick = { onPodcastClick(podcast) }
            )
        }
    }
}

@Composable
fun PodcastCard(
    podcast: Podcast,
    onClick: () -> Unit
) {
    MinimalGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        contentPadding = 12.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            podcast.imageUrl?.let { imageUrl ->
                AsyncImage(
                    model = imageUrl,
                    contentDescription = podcast.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
            } ?: Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = podcast.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun EmptyPodcastsView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                MusicNote,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "No podcasts yet",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Import an OPML file to get started",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun PodcastDetailView(
    podcast: Podcast,
    episodes: List<PodcastEpisode>?,
    isLoadingEpisodes: Boolean,
    isLandscape: Boolean,
    onBack: () -> Unit,
    onLoadEpisodes: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header with back button and podcast info
        PrimaryGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                podcast.imageUrl?.let { imageUrl ->
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = podcast.title,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = podcast.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    podcast.websiteUrl?.let { url ->
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                if (episodes == null && !isLoadingEpisodes) {
                    FilledTonalButton(onClick = onLoadEpisodes) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Load", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Episodes content
        when {
            isLoadingEpisodes -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Loading episodes...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            episodes != null -> {
                EpisodesList(
                    episodes = episodes,
                    isLandscape = isLandscape
                )
            }
            
            else -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Episodes not loaded",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Tap Load to fetch recent episodes",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodesList(
    episodes: List<PodcastEpisode>,
    isLandscape: Boolean,
    onPlayEpisode: (PodcastEpisode) -> Unit = { episode ->
        // Log for now since we don't have access to viewModel
        android.util.Log.d("PodcastTab", "Would play episode: ${episode.title}")
    }
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        items(episodes) { episode ->
            RichEpisodeItem(
                episode = episode,
                onPlayEpisode = onPlayEpisode
            )
        }
    }
}

@Composable
fun RichEpisodeItem(
    episode: PodcastEpisode,
    onPlayEpisode: (PodcastEpisode) -> Unit = { episode ->
        android.util.Log.d("PodcastTab", "Would play episode: ${episode.title}")
    }
) {
    var expanded by remember { mutableStateOf(false) }
    
    MinimalGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        contentPadding = 16.dp
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        episode.publishDate?.let { date ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.DateRange,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = date,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        episode.duration?.let { duration ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = duration,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    episode.audioUrl?.let {
                        IconButton(
                            onClick = { onPlayEpisode(episode) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Play Episode",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Show Less" else "Show More",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (expanded) {
                episode.description?.let { description ->
                    if (description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                        )
                    }
                }
            }
        }
    }
}