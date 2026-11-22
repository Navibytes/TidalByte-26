package com.tidalbyte.stridesync.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tidalbyte.stridesync.MainViewModel

enum class Tab(val label: String, val icon: ImageVector) {
    WALK("Walk", SsIcons.Walk),
    MUSIC("Music", SsIcons.Music),
    HISTORY("History", SsIcons.History),
    PROFILE("Profile", SsIcons.Profile),
}

/** The app shell: four tabs with the bottom nav from the mockups, and the session-settings sheet. */
@Composable
fun StrideSyncApp(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.WALK) }
    var showSettings by remember { mutableStateOf(false) }

    // Android 13+: ask once for notification permission (the session notification). Start either way.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val onStart = {
        val needAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needAsk) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }
    // A finished session may have produced a new recording.
    LaunchedEffect(state.running) { if (!state.running) vm.refreshReplays() }
    BackHandler(enabled = tab != Tab.WALK) { tab = Tab.WALK }

    Scaffold(
        containerColor = Ss.Bg,
        bottomBar = { BottomNav(tab) { tab = it } },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                Tab.WALK -> WalkScreen(vm, state, onStart, onOpenSettings = { showSettings = true }, onOpenMusic = { tab = Tab.MUSIC })
                Tab.MUSIC -> MusicScreen(vm, state)
                Tab.HISTORY -> HistoryScreen()
                Tab.PROFILE -> ProfileScreen(vm, onOpenSettings = { showSettings = true }, onOpenHistory = { tab = Tab.HISTORY })
            }
        }
    }
    if (showSettings) SettingsSheet(vm, state) { showSettings = false }
}

@Composable
private fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.background(Ss.Bg)) {
        HorizontalDivider(thickness = 1.dp, color = Ss.Line)
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Tab.entries.forEach { t ->
                val sel = t == selected
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier
                            .defaultMinSize(minWidth = 76.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (sel) Ss.Pink else Color.Transparent)
                            .clickable { onSelect(t) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(t.icon, t.label, tint = if (sel) Ss.Red else Ss.Ink, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(3.dp))
                        Text(
                            t.label,
                            fontSize = 13.5.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (sel) Ss.RedDeep else Ss.Ink,
                        )
                    }
                }
            }
        }
    }
}
