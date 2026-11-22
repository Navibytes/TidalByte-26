package com.tidalbyte.stridesync.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tidalbyte.stridesync.MainViewModel
import com.tidalbyte.stridesync.audio.CueSound
import com.tidalbyte.stridesync.session.LiveState

/** The beat sound picker. The bundled song, Click and Woodblock are real; "From your music" is FAKE. */
@Composable
fun MusicScreen(vm: MainViewModel, state: LiveState) {
    val context = LocalContext.current
    val demoOnly = { Toast.makeText(context, "Demo: only the bundled song can play for now.", Toast.LENGTH_SHORT).show() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(22.dp))
        Text("Music", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Ss.Ink, letterSpacing = (-0.5).sp)
        Text(
            "Songs speed up and slow down to match your steps.",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Ss.Muted,
        )
        Spacer(Modifier.height(20.dp))

        SectionTitle("Your beat song")
        Spacer(Modifier.height(10.dp))
        val song = vm.song
        if (song != null) {
            ChoiceRow(
                leading = { SongArt(54.dp, playing = vm.cueSound == CueSound.MUSIC && state.running && state.cueBpm != null) },
                title = song.shortTitle,
                subtitle = "%.0f bpm · plays at your pace".format(song.bpm),
                selected = vm.cueSound == CueSound.MUSIC,
                onClick = { vm.updateCueSound(CueSound.MUSIC) },
            )
        } else {
            Text(
                "No song added yet. Prepare one with tools/prepare_song.py and rebuild the app.",
                fontSize = 14.5.sp,
                color = Ss.Muted,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Ss.Card)
                    .padding(16.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionTitle("Simple beats")
        Spacer(Modifier.height(10.dp))
        ChoiceRow(
            leading = { IconTile(SsIcons.Click) },
            title = "Click",
            subtitle = "A crisp tick on every step",
            selected = vm.cueSound == CueSound.CLICK,
            onClick = { vm.updateCueSound(CueSound.CLICK) },
        )
        Spacer(Modifier.height(10.dp))
        ChoiceRow(
            leading = { IconTile(SsIcons.Woodblock) },
            title = "Woodblock",
            subtitle = "A softer, woody knock",
            selected = vm.cueSound == CueSound.WOODBLOCK,
            onClick = { vm.updateCueSound(CueSound.WOODBLOCK) },
        )

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("From your music", Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Ss.Card)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("Demo", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Ss.Muted) }
        }
        Spacer(Modifier.height(10.dp))
        listOf("Sunday Stroll" to 98, "Morning Light" to 92, "Garden Path" to 101).forEachIndexed { i, (title, bpm) ->
            ChoiceRow(
                leading = { SongArt(54.dp, tone = i + 1) },
                title = title,
                subtitle = "$bpm bpm · from your music",
                selected = null,
                onClick = demoOnly,
            )
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row {
            OutlinePill("+ Add songs", demoOnly)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** A white row card: leading art, title/subtitle, and a selection ring ([selected] = null shows a + instead). */
@Composable
private fun ChoiceRow(leading: @Composable () -> Unit, title: String, subtitle: String, selected: Boolean?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected == true) Ss.Pink else Color.White)
            .border(1.5.dp, if (selected == true) Ss.PinkBorder else Ss.Border, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ss.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ss.Muted)
        }
        Spacer(Modifier.width(10.dp))
        if (selected != null) {
            RadioDot(selected)
        } else {
            Icon(SsIcons.Plus, "Add", tint = Ss.Muted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Ss.Card),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Ss.Ink, modifier = Modifier.size(28.dp))
    }
}
