package com.snag.downloader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun SnagRoot(vm: SnagViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val message = vm.message
    val scheme = MaterialTheme.colorScheme
    val colors = snagColors()
    var booting by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(600)
        booting = false
    }

    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        vm.consumeMessage()
        snackbar.showSnackbar(message)
    }

    SnagBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = {
                SnackbarHost(snackbar) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = RoundedCornerShape(10.dp),
                        containerColor = scheme.inverseSurface,
                        contentColor = scheme.inverseOnSurface,
                        actionColor = scheme.inversePrimary,
                        dismissActionContentColor = scheme.inverseOnSurface.copy(alpha = 0.70f),
                    )
                }
            },
            bottomBar = {
                SnagBottomBar(selected = vm.tab, onSelect = vm::selectTab)
            },
        ) { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .imePadding(),
            ) {
                Crossfade(
                    targetState = vm.tab,
                    animationSpec = tween(SnagMotion.ms(150)),
                    modifier = Modifier.fillMaxSize(),
                    label = "tab-content",
                ) { tab ->
                    when (tab) {
                        Tab.SAVE -> SaveScreen(vm)
                        Tab.LIBRARY -> LibraryScreen(vm)
                        Tab.SETTINGS -> SettingsScreen(vm)
                    }
                }
                AnimatedVisibility(
                    visible = booting,
                    enter = fadeIn(tween(0)),
                    exit = fadeOut(tween(SnagMotion.ms(320))),
                ) {
                    SnagSkeletonPage(
                        tab = vm.tab,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SnagBottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = snagColors()

    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.surface),
    ) {
        HorizontalDivider(thickness = 1.dp, color = scheme.outline)
        NavigationBar(
            containerColor = scheme.surface,
            tonalElevation = 0.dp,
        ) {
            Tab.entries.forEach { tab ->
                val active = tab == selected
                val scale by animateFloatAsState(
                    targetValue = if (active) 1.14f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = SnagMotion.stiffness(Spring.StiffnessMediumLow),
                    ),
                    label = "nav-${tab.name}",
                )
                NavigationBarItem(
                    selected = active,
                    onClick = { onSelect(tab) },
                    icon = {
                        Icon(
                            imageVector = tabIcon(tab, active),
                            contentDescription = null,
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                },
                        )
                    },
                    label = { Text(tab.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.accent,
                        selectedTextColor = colors.accent,
                        indicatorColor = colors.gold.copy(alpha = 0.20f),
                        unselectedIconColor = scheme.onSurfaceVariant,
                        unselectedTextColor = scheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

private fun tabIcon(tab: Tab, active: Boolean): ImageVector = when (tab) {
    Tab.SAVE -> if (active) Icons.Rounded.Download else Icons.Outlined.Download
    Tab.LIBRARY -> if (active) Icons.Rounded.VideoLibrary else Icons.Outlined.VideoLibrary
    Tab.SETTINGS -> if (active) Icons.Rounded.Settings else Icons.Outlined.Settings
}
