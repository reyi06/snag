package com.snag.downloader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.snag.downloader.core.Codec
import com.snag.downloader.core.MediaBundle
import com.snag.downloader.core.MediaKind
import com.snag.downloader.core.MediaVariant
import com.snag.downloader.core.ProfileBundle
import com.snag.downloader.core.SourcePlatform
import com.snag.downloader.core.StrategyFailure
import com.snag.downloader.core.bestVariant
import com.snag.downloader.data.QualityPreference
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SaveScreen(vm: SnagViewModel) {
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val loading = vm.state is ResolveState.Loading
    val profile = (vm.state as? ResolveState.Profile)?.profile
    var profileOpen by rememberSaveable(profile?.url) { mutableStateOf(false) }
    var handledProfileRequest by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val startSignIn = rememberTikTokSignIn {
        vm.markTikTokSignedIn()
        vm.resolve()
    }
    val startVerify = rememberTikTokProfileVerify(
        onItems = { raw ->
            vm.applyProfileItems(raw)
        },
        onVerified = vm::reloadVerifiedProfile,
    )

    LaunchedEffect(Unit) { vm.refreshHistory() }

    LaunchedEffect(vm.profileSheetRequest, profile?.url) {
        if (profile != null && vm.profileSheetRequest > handledProfileRequest) {
            handledProfileRequest = vm.profileSheetRequest
            profileOpen = true
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 720.dp)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 16.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                ScreenHeader(
                    title = "Snag",
                    leading = { RoyalMark(Modifier.size(22.dp)) },
                )
            }

            item {
                SaveHero(
                    input = vm.input,
                    loading = loading,
                    onInputChanged = vm::onInputChanged,
                    onClear = vm::clearInput,
                    onPaste = { vm.paste(clipboard.getText()?.text) },
                    onResolve = {
                        keyboard?.hide()
                        vm.resolve()
                    },
                )
            }

            item { FactStrip() }

            item {
                AnimatedContent(
                    targetState = vm.state,
                    transitionSpec = {
                        (
                            fadeIn(tween(SnagMotion.ms(240))) +
                                slideInVertically(tween(SnagMotion.ms(280))) { height ->
                                    height / 16
                                }
                            ).togetherWith(
                            fadeOut(tween(SnagMotion.ms(160)))
                        ).using(SizeTransform(clip = false))
                    },
                    label = "resolve-content",
                ) { state ->
                    when (state) {
                        ResolveState.Idle -> Spacer(Modifier.height(0.dp))
                        ResolveState.Loading -> ResolvingCard()
                        is ResolveState.Failed -> FailureCard(
                            state = state,
                            onRetry = { vm.resolve() },
                        )

                        is ResolveState.Media -> MediaResult(
                            bundle = state.bundle,
                            failures = state.failures,
                            preferH265 = vm.preferH265,
                            preferHighest = vm.preferHighest,
                            onSave = vm::save,
                            onSaveImages = vm::saveAllImages,
                            onSaveAudio = vm::saveAudio,
                            onAuthorClick = { vm.openAuthorProfile(state.bundle) },
                            quality = vm.quality,
                            onQualityChange = vm::updateQuality,
                        )

                        is ResolveState.Profile -> ProfileCard(
                            profile = state.profile,
                            signedIn = vm.tiktokSignedIn,
                            bookmarked = vm.isBookmarked(state.profile),
                            onBookmark = { vm.toggleBookmark(state.profile) },
                            onClick = { profileOpen = true },
                            onSignIn = startSignIn,
                            onVerify = {
                                scope.launch {
                                    if (vm.validateTikTokSession()) {
                                        startVerify(state.profile.url)
                                    }
                                }
                            },
                            onOpenExternal = { vm.openProfile(state.profile) },
                        )
                    }
                }
            }

            if (vm.history.isNotEmpty()) {
                item {
                    SectionHeader("Recent") {
                        SnagTextButton(
                            text = "Library",
                            onClick = { vm.selectTab(Tab.LIBRARY) },
                        )
                    }
                }
                item {
                    SnagCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(10.dp),
                    ) {
                        GalleryGrid(
                            entries = vm.history.take(3),
                            onOpen = vm::openEntry,
                        )
                    }
                }
            }
        }
    }

    if (profileOpen && profile != null) {
        ProfileSheet(
            profile = profile,
            bookmarked = vm.isBookmarked(profile),
            onToggleBookmark = { vm.toggleBookmark(profile) },
            vm = vm,
            onClose = { profileOpen = false },
        )
    }
}

@Composable
private fun SaveHero(
    input: String,
    loading: Boolean,
    onInputChanged: (String) -> Unit,
    onClear: () -> Unit,
    onPaste: () -> Unit,
    onResolve: () -> Unit,
) {
    val colors = snagColors()
    val scheme = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current

    SnagFieldCard(Modifier.fillMaxWidth()) {
        HeroCopy()

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = input,
            onValueChange = onInputChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    "Paste a link",
                    color = scheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (input.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear the link",
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onClear)
                            .padding(10.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(
                onGo = {
                    keyboard?.hide()
                    onResolve()
                }
            ),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = scheme.surface,
                unfocusedContainerColor = scheme.surface,
                cursorColor = colors.accent,
                focusedTextColor = scheme.onSurface,
                unfocusedTextColor = scheme.onSurface,
                focusedPlaceholderColor = scheme.onSurfaceVariant,
                unfocusedPlaceholderColor = scheme.onSurfaceVariant,
            ),
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SnagSecondaryButton(
                text = "Paste",
                onClick = onPaste,
                icon = Icons.Rounded.ContentPaste,
                onField = true,
            )
            SnagPrimaryButton(
                text = if (loading) "Reading…" else "Find media",
                onClick = onResolve,
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Search,
                enabled = input.isNotBlank(),
                loading = loading,
            )
        }
    }
}

@Composable
private fun HeroCopy(modifier: Modifier = Modifier) {
    val colors = snagColors()
    Column(modifier) {
        Text(
            "Paste a link.",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onField,
        )
    }
}

@Composable
private fun FactStrip() {
    val scheme = MaterialTheme.colorScheme
    val colors = snagColors()
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceVariant)
            .border(1.dp, colors.gold.copy(alpha = 0.28f), shape)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Fact("On-device", Modifier.weight(1f))
        GoldDiamond(Modifier.padding(horizontal = 2.dp))
        Fact("FHD · H.265", Modifier.weight(1f))
        GoldDiamond(Modifier.padding(horizontal = 2.dp))
        Fact("No watermark", Modifier.weight(1f))
    }
}

@Composable
private fun Fact(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        maxLines = 2,
    )
}

@Composable
private fun ResolvingCard() {
    SnagCard(Modifier.fillMaxWidth()) {
        val colors = snagColors()
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = colors.accent,
            )
            Spacer(Modifier.width(14.dp))
            Text("Reading link…", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun FailureCard(state: ResolveState.Failed, onRetry: () -> Unit) {
    var details by remember(state) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    SnagCard(Modifier.fillMaxWidth()) {
        Row {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = scheme.error,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Couldn't read link", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.padding(top = 3.dp))
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnagPrimaryButton(
                text = "Try again",
                onClick = onRetry,
            )
            if (state.failures.isNotEmpty()) {
                SnagTextButton(
                    text = if (details) "Hide details" else "Details (${state.failures.size})",
                    onClick = { details = !details },
                )
            }
        }
        if (details) {
            Spacer(Modifier.height(8.dp))
            SnagDivider()
            state.failures.forEach { failure ->
                Text(
                    "${failure.strategy} · ${failure.message}",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MediaResult(
    bundle: MediaBundle,
    failures: List<StrategyFailure>,
    preferH265: Boolean,
    preferHighest: Boolean,
    onSave: (MediaBundle, MediaVariant) -> Unit,
    onSaveImages: (MediaBundle, List<MediaVariant>) -> Unit,
    onSaveAudio: (MediaBundle) -> Unit,
    onAuthorClick: () -> Unit,
    quality: QualityPreference,
    onQualityChange: (QualityPreference) -> Unit,
) {
    val videos = remember(bundle) {
        bundle.variants
            .filter { it.kind == MediaKind.VIDEO }
            .distinctBy { chipLabel(it) }
    }
    val images = remember(bundle) {
        bundle.variants.filter { it.kind == MediaKind.IMAGE }
    }

    val clean = remember(bundle) {
        bundle.variants.filter { it.label != "watermarked" }.ifEmpty { bundle.variants }
    }
    val selectedVariant = remember(bundle, preferH265, preferHighest) {
        bundle.copy(variants = clean).bestVariant(preferH265, preferHighest)
    }
    var details by remember(bundle.id) { mutableStateOf(false) }
    var qualityOpen by rememberSaveable(bundle.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val colors = snagColors()

    SnagCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
    ) {
        bundle.coverUrl?.let { cover ->
            AsyncImage(
                model = cover,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentScale = ContentScale.Crop,
            )
        }

        Column(Modifier.padding(16.dp)) {
            val linkedAuthor = bundle.platform == SourcePlatform.TIKTOK &&
                !bundle.author?.handle.isNullOrBlank()
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        if (linkedAuthor) Modifier.clickable(role = Role.Button, onClick = onAuthorClick)
                        else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = bundle.author?.display ?: bundle.platform.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (linkedAuthor) colors.accent else scheme.onSurfaceVariant,
                )
                if (linkedAuthor) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = "Open creator profile",
                        modifier = Modifier.size(16.dp),
                        tint = colors.accent,
                    )
                }
                if (bundle.author != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "· ${bundle.platform.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            if (images.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SnagPrimaryButton(
                    text = if (images.size == 1) "Save photo"
                    else "Save all ${images.size} photos",
                    onClick = { onSaveImages(bundle, images) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.Download,
                )
            } else if (videos.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SnagDivider()
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Quality",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = { qualityOpen = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = "Choose default quality",
                            modifier = Modifier.size(20.dp),
                            tint = colors.accent,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        quality.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SnagPrimaryButton(
                        text = "Save video",
                        onClick = { selectedVariant?.let { onSave(bundle, it) } },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Download,
                        enabled = selectedVariant != null,
                    )
                    if (bundle.audioUrl != null) {
                        SnagSecondaryButton(
                            text = "Audio",
                            onClick = { onSaveAudio(bundle) },
                            icon = Icons.Rounded.MusicNote,
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Text(
                    "No downloadable files in this post.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            if (failures.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SnagDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { details = !details }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (details) "Hide details" else "Details (${failures.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accent,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (details) Icons.Rounded.Close else Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = colors.accent,
                    )
                }
                if (details) {
                    failures.forEach { failure ->
                        Text(
                            "${failure.strategy} · ${failure.message}",
                            modifier = Modifier.padding(bottom = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (qualityOpen) {
        QualityDialog(
            selected = quality,
            onSelect = onQualityChange,
            onDismiss = { qualityOpen = false },
        )
    }
}

@Composable
private fun QualityDialog(
    selected: QualityPreference,
    onSelect: (QualityPreference) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Default quality",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(10.dp))
                QualityPreference.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) },
                            )
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = null,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            option.title,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: ProfileBundle,
    signedIn: Boolean,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onClick: () -> Unit,
    onSignIn: () -> Unit,
    onVerify: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = snagColors()
    val enabled = profile.items.isNotEmpty()
    val shape = RoundedCornerShape(12.dp)

    SnagCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(profile, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile.nickname ?: profile.handle,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "@${profile.handle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (enabled) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "Open profile",
                    modifier = Modifier.size(24.dp),
                    tint = colors.gold,
                )
            }
            IconButton(
                onClick = onBookmark,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (bookmarked) Icons.Rounded.Bookmark
                    else Icons.Rounded.BookmarkBorder,
                    contentDescription = if (bookmarked) "Remove profile bookmark"
                    else "Bookmark profile",
                    tint = if (bookmarked) colors.gold else scheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        SnagDivider()
        Spacer(Modifier.height(12.dp))
        ProfileStats(profile)

        if (profile.isPartial) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Recent posts only — open to retry the full profile",
                style = MaterialTheme.typography.labelSmall,
                color = colors.gold,
            )
        }

        if (!enabled) {
            Spacer(Modifier.height(12.dp))
            SnagPrimaryButton(
                text = if (signedIn) "Verify on TikTok" else "Sign in to TikTok",
                onClick = if (signedIn) onVerify else onSignIn,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            SnagSecondaryButton(
                text = "Open on TikTok",
                onClick = onOpenExternal,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ProfileAvatar(profile: ProfileBundle, size: androidx.compose.ui.unit.Dp) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(scheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (profile.avatarUrl != null) {
            AsyncImage(
                model = profile.avatarUrl,
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

@Composable
private fun ProfileStats(profile: ProfileBundle) {
    val count = if (profile.videoCount > 0) profile.videoCount else profile.items.size.toLong()
    Row {
        ProfileStat(formatCount(profile.followerCount), "followers")
        Spacer(Modifier.width(24.dp))
        ProfileStat(
            formatCount(count),
            if (profile.videoCount > 0) "videos" else "recent",
        )
    }
}

@Composable
private fun ProfileStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheet(
    profile: ProfileBundle,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    vm: SnagViewModel,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheme = MaterialTheme.colorScheme
    val colors = snagColors()

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
        containerColor = scheme.background,
        contentColor = scheme.onBackground,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = colors.gold.copy(alpha = 0.65f))
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Select videos",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        imageVector = if (bookmarked) Icons.Rounded.Bookmark
                        else Icons.Rounded.BookmarkBorder,
                        contentDescription = if (bookmarked) "Remove profile bookmark"
                        else "Bookmark profile",
                        tint = if (bookmarked) colors.gold else scheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }

            SnagCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(profile, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile.nickname ?: profile.handle,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "@${profile.handle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                if (!profile.bio.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        profile.bio,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                SnagDivider()
                Spacer(Modifier.height(10.dp))
                ProfileStats(profile)
            }

            Spacer(Modifier.height(14.dp))

            if (profile.isPartial) {
                SnagCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column {
                        Text(
                            "Recent posts only",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.gold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            profile.note
                                ?: "TikTok did not return the full profile.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                        SnagTextButton(
                            text = "Retry full profile",
                            onClick = { vm.retryFullProfile() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            if (profile.items.isEmpty()) {
                SnagEmptyState(
                    icon = Icons.Rounded.VideoLibrary,
                    title = "No public videos",
                    body = profile.note ?: "This profile did not expose any posts.",
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(profile.items, key = { it.id }) { item ->
                        ProfileCell(
                            bundle = item,
                            selected = vm.selected[item.id] == true,
                            onToggle = { vm.toggleSelection(item.id) },
                        )
                    }
                }

                ProfileActionBar(
                    profile = profile,
                    vm = vm,
                    onDownloadSelected = {
                        vm.saveSelected(profile)
                        onClose()
                    },
                    onDownloadAll = {
                        vm.saveAll(profile)
                        onClose()
                    },
                )
            }
        }
    }
}

@Composable
private fun ProfileActionBar(
    profile: ProfileBundle,
    vm: SnagViewModel,
    onDownloadSelected: () -> Unit,
    onDownloadAll: () -> Unit,
) {
    val count = vm.selectedCount()
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.surface),
    ) {
        SnagDivider()
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (count == 0) "0 selected" else "$count selected",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    vm.quality.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SnagPrimaryButton(
                    text = if (count == 0) "Download" else "Download $count",
                    onClick = onDownloadSelected,
                    modifier = Modifier.weight(1f),
                    enabled = count > 0,
                )
                SnagSecondaryButton(
                    text = "Download all",
                    onClick = onDownloadAll,
                    modifier = Modifier.weight(1f),
                    enabled = profile.items.isNotEmpty(),
                )
            }
        }
    }
}

@Composable
private fun ProfileCell(
    bundle: MediaBundle,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = snagColors()
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .aspectRatio(3f / 4f)
            .clip(shape)
            .background(scheme.surfaceVariant)
            .toggleable(
                value = selected,
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .border(
                width = if (selected || focused) 2.dp else 1.dp,
                color = when {
                    selected -> colors.gold
                    focused -> colors.accent
                    else -> scheme.outline
                },
                shape = shape,
            ),
    ) {
        if (bundle.coverUrl != null) {
            AsyncImage(
                model = bundle.coverUrl,
                contentDescription = bundle.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Rounded.VideoLibrary,
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center),
                tint = scheme.onSurfaceVariant,
            )
        }

        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.gold.copy(alpha = 0.16f))
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (selected) colors.gold else colors.field.copy(alpha = 0.40f)
                )
                .border(
                    1.dp,
                    if (selected) colors.onGold.copy(alpha = 0.20f)
                    else colors.gold.copy(alpha = 0.65f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "Selected",
                    modifier = Modifier.size(15.dp),
                    tint = colors.onGold,
                )
            }
        }
    }
}

private fun chipLabel(variant: MediaVariant): String = buildString {
    append(
        when {
            variant.height > 0 -> "${variant.height}p"
            !variant.label.isNullOrBlank() -> variant.label
            else -> "Video"
        }
    )
    if (variant.label == "watermarked") append(" · watermark")
    if (variant.codec == Codec.H265) append(" · H.265")
}

private fun formatCount(value: Long): String {
    val text = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
    return text.replace(".0M", "M").replace(".0K", "K")
}
