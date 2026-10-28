package com.snag.downloader.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.snag.downloader.data.DownloadStatus
import com.snag.downloader.data.HistoryEntry
import com.snag.downloader.data.ProfileBookmark
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class LibraryFilter(val label: String) {
    ALL("All"),
    VIDEOS("Videos"),
    IMAGES("Images"),
    AUDIO("Audio"),
    FAILED("Failed"),
}

@Composable
fun LibraryScreen(vm: SnagViewModel) {
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    LaunchedEffect(Unit) { vm.refreshHistory() }
    val all = vm.history.toList()
    val filtered = remember(all, filter) { all.filter { filter.matches(it) } }
    val groups = remember(filtered) { filtered.groupBy { dayLabel(it.createdAt) } }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 720.dp)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ScreenHeader("Library", subtitle = summary(all)) }

            if (vm.profileBookmarks.isNotEmpty()) {
                item {
                    SectionHeader("Profiles") {
                        Text(
                            "${vm.profileBookmarks.size} saved",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    SnagCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        vm.profileBookmarks.forEachIndexed { index, bookmark ->
                            if (index > 0) SnagDivider()
                            BookmarkRow(
                                bookmark = bookmark,
                                onOpen = { vm.openBookmark(bookmark) },
                                onRemove = { vm.removeBookmark(bookmark) },
                            )
                        }
                    }
                }
            }

            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(LibraryFilter.entries, key = { it.name }) { option ->
                        SnagChip(
                            text = option.label,
                            selected = filter == option,
                            onClick = { filter = option },
                            role = Role.Tab,
                            showCheck = false,
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    if (all.isEmpty()) {
                        SnagEmptyState(
                            icon = Icons.Rounded.VideoLibrary,
                            title = "Nothing saved",
                            body = "Saved files and failed downloads appear here.",
                        )
                    } else {
                        SnagEmptyState(
                            icon = Icons.Rounded.VideoLibrary,
                            title = "No files in this filter",
                            body = "Try another filter or return to All.",
                        )
                    }
                }
            } else {
                groups.forEach { (label, entries) ->
                    item(key = "day-$label") {
                        Text(
                            text = label,
                            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item(key = "group-$label") {
                        SnagCard(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(10.dp),
                        ) {
                            GalleryGrid(
                                entries = entries,
                                onOpen = vm::openEntry,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun summary(entries: List<HistoryEntry>): String {
    val done = entries.filter { it.status == DownloadStatus.DONE }
    val failed = entries.count { it.status == DownloadStatus.FAILED }
    val bytes = done.sumOf { it.bytes }
    return buildString {
        append(done.size).append(" saved")
        if (bytes > 0) append(" · ").append(formatBytes(bytes))
        if (failed > 0) append(" · ").append(failed).append(" failed")
    }
}

/** A photos-style grid of saved items. Shared with the Save screen's "Recent" section. */
@Composable
fun GalleryGrid(
    entries: List<HistoryEntry>,
    onOpen: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { entry ->
                    GalleryTile(
                        entry = entry,
                        onClick = { onOpen(entry) },
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                    )
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GalleryTile(
    entry: HistoryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = snagColors()
    val shape = RoundedCornerShape(10.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val statusLabel = when (entry.status) {
        DownloadStatus.DONE -> "Saved"
        DownloadStatus.RUNNING -> "Downloading"
        DownloadStatus.QUEUED -> "Queued"
        DownloadStatus.FAILED -> "Failed"
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(scheme.surfaceVariant)
            .then(if (focused) Modifier.border(2.dp, colors.accent, shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "${title(entry)}. ${meta(entry)}. $statusLabel"
            },
        contentAlignment = Alignment.Center,
    ) {
        val thumbModel = if (entry.status == DownloadStatus.DONE &&
            entry.mimeType.startsWith("image/") && entry.localUri != null
        ) {
            entry.localUri
        } else {
            entry.thumbnailUrl
        }
        if (thumbModel != null) {
            AsyncImage(
                model = thumbModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = kindIcon(entry),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (entry.status == DownloadStatus.FAILED) scheme.error
                    else colors.field.copy(alpha = 0.78f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (entry.status) {
                DownloadStatus.DONE -> Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.onField,
                )

                DownloadStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = colors.gold,
                )

                DownloadStatus.QUEUED -> Icon(
                    Icons.Rounded.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.onField,
                )

                DownloadStatus.FAILED -> Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = scheme.onError,
                )
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: ProfileBookmark,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = snagColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookmarkAvatar(bookmark)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = bookmark.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "@${bookmark.handle}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.accent,
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Rounded.Bookmark,
                contentDescription = "Remove @${bookmark.handle} bookmark",
                tint = colors.gold,
            )
        }
    }
}

@Composable
private fun BookmarkAvatar(bookmark: ProfileBookmark) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(scheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bookmark.avatarUrl != null) {
            AsyncImage(
                model = bookmark.avatarUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}

private fun LibraryFilter.matches(entry: HistoryEntry): Boolean = when (this) {
    LibraryFilter.ALL -> true
    LibraryFilter.VIDEOS -> entry.mimeType.startsWith("video/")
    LibraryFilter.IMAGES -> entry.mimeType.startsWith("image/")
    LibraryFilter.AUDIO -> entry.mimeType.startsWith("audio/")
    LibraryFilter.FAILED -> entry.status == DownloadStatus.FAILED
}

private fun kindIcon(entry: HistoryEntry): ImageVector = when {
    entry.mimeType.startsWith("video/") -> Icons.Rounded.Movie
    entry.mimeType.startsWith("image/") -> Icons.Rounded.Image
    entry.mimeType.startsWith("audio/") -> Icons.Rounded.MusicNote
    else -> Icons.Rounded.Description
}

private fun title(entry: HistoryEntry): String =
    entry.title?.takeIf { it.isNotBlank() } ?: when {
        entry.mimeType.startsWith("video/") -> "Untitled video"
        entry.mimeType.startsWith("image/") -> "Photo"
        entry.mimeType.startsWith("audio/") -> "Audio"
        else -> entry.fileName
    }

private fun meta(entry: HistoryEntry): String = listOfNotNull(
    platformLabel(entry.platform),
    entry.variantLabel,
    relativeTime(entry.createdAt),
).joinToString(" · ")

private fun platformLabel(id: String): String? = when (id) {
    "youtube" -> "YouTube"
    "tiktok" -> "TikTok"
    "instagram" -> "Instagram"
    "facebook" -> "Facebook"
    "generic" -> "Link"
    "unknown" -> null
    else -> id.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

private fun dayLabel(timestamp: Long): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(
            DateTimeFormatter.ofPattern(
                if (date.year == today.year) "MMMM d" else "MMMM d, yyyy",
            )
        )
    }
}

private fun relativeTime(timestamp: Long): String {
    val then = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    val now = java.time.ZonedDateTime.now()
    val minutes = ChronoUnit.MINUTES.between(then, now)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        then.toLocalDate() == now.toLocalDate() -> "${ChronoUnit.HOURS.between(then, now)}h ago"
        then.toLocalDate() == now.toLocalDate().minusDays(1) -> "yesterday"
        else -> then.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 KB"
    val kb = bytes / 1024.0
    return when {
        kb < 1024 -> "${kb.toInt().coerceAtLeast(1)} KB"
        kb < 1024 * 1024 -> String.format(Locale.US, "%.1f MB", kb / 1024)
        else -> String.format(Locale.US, "%.1f GB", kb / 1024 / 1024)
    }
}
