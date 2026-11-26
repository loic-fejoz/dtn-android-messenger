package io.github.loic_fejoz.dtn_android_messenger.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun AudioPlayerCard(
    filePath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember(filePath) { mutableStateOf(false) }
    var isPlaying by remember(filePath) { mutableStateOf(false) }
    var duration by remember(filePath) { mutableStateOf(0) }
    var currentPosition by remember(filePath) { mutableStateOf(0) }
    var focusToken by remember(filePath) { mutableStateOf<Any?>(null) }

    val focusChangeListener =
        remember(filePath) {
            AudioManager.OnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    val p = mediaPlayer
                    if (p != null && isPrepared && isPlaying) {
                        runCatching { p.pause() }
                        isPlaying = false
                    }
                }
            }
        }

    fun abandonFocus() {
        val am = audioManager ?: return
        AudioFocusHelper.abandonFocus(am, focusChangeListener, focusToken)
        focusToken = null
    }

    fun requestFocus(): Boolean {
        val am = audioManager ?: return true
        val (granted, token) = AudioFocusHelper.requestFocus(am, focusChangeListener)
        focusToken = token
        return granted
    }

    // Initialize MediaPlayer
    DisposableEffect(filePath) {
        isPrepared = false
        val player =
            MediaPlayer().apply {
                try {
                    if (filePath.startsWith("content://") || filePath.startsWith("file://")) {
                        val uri = Uri.parse(filePath)
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        }
                    } else {
                        setDataSource(filePath)
                    }
                    setOnPreparedListener { mp ->
                        duration = mp.duration
                        isPrepared = true
                    }
                    setOnErrorListener { _, what, extra ->
                        android.util.Log.e("AudioPlayerCard", "MediaPlayer error: what=$what, extra=$extra for $filePath")
                        isPrepared = false
                        isPlaying = false
                        true
                    }
                    prepareAsync()
                } catch (e: Exception) {
                    android.util.Log.e("AudioPlayerCard", "Error preparing MediaPlayer for $filePath", e)
                }
            }
        mediaPlayer = player

        player.setOnCompletionListener {
            isPlaying = false
            currentPosition = 0
            abandonFocus()
            if (isPrepared) {
                try {
                    player.seekTo(0)
                } catch (_: IllegalStateException) {
                }
            }
        }

        onDispose {
            isPrepared = false
            isPlaying = false
            abandonFocus()
            try {
                player.stop()
            } catch (_: IllegalStateException) {
            }
            player.release()
            mediaPlayer = null
        }
    }

    // Progress timer tick
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isPlaying && mediaPlayer != null) {
                currentPosition = mediaPlayer?.currentPosition ?: 0
                delay(250)
            }
        }
    }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x11FFFFFF)),
        border = BorderStroke(1.dp, Color(0x1AFFFFFF)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = {
                    val player = mediaPlayer ?: return@IconButton
                    if (!isPrepared) return@IconButton
                    if (isPlaying) {
                        player.pause()
                        isPlaying = false
                        abandonFocus()
                    } else {
                        if (requestFocus()) {
                            player.start()
                            isPlaying = true
                        }
                    }
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = NeonCyan,
                    modifier = Modifier.size(28.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Slider(
                    value = currentPosition.toFloat(),
                    onValueChange = { value ->
                        if (!isPrepared) return@Slider
                        currentPosition = value.toInt()
                        mediaPlayer?.seekTo(currentPosition)
                    },
                    valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                    colors =
                        SliderDefaults.colors(
                            thumbColor = NeonCyan,
                            activeTrackColor = NeonCyan,
                            inactiveTrackColor = Color(0x33FFFFFF),
                        ),
                    modifier = Modifier.height(24.dp),
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatMs(currentPosition),
                        fontSize = 11.sp,
                        color = TextGray,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = formatMs(duration),
                        fontSize = 11.sp,
                        color = TextGray,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

private fun formatMs(ms: Int): String {
    val totalSecs = ms / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

private object AudioFocusHelper {
    fun requestFocus(
        audioManager: AudioManager,
        listener: AudioManager.OnAudioFocusChangeListener,
    ): Pair<Boolean, Any?> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Api26.requestFocus(audioManager, listener)
        } else {
            @Suppress("DEPRECATION")
            val granted =
                audioManager.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            granted to null
        }
    }

    fun abandonFocus(
        audioManager: AudioManager,
        listener: AudioManager.OnAudioFocusChangeListener,
        token: Any?,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Api26.abandonFocus(audioManager, token)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private object Api26 {
        fun requestFocus(
            audioManager: AudioManager,
            listener: AudioManager.OnAudioFocusChangeListener,
        ): Pair<Boolean, Any?> {
            val req =
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener(listener)
                    .build()
            val granted = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return granted to req
        }

        fun abandonFocus(
            audioManager: AudioManager,
            token: Any?,
        ) {
            val req = token as? AudioFocusRequest ?: return
            audioManager.abandonAudioFocusRequest(req)
        }
    }
}
