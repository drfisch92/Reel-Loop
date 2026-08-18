package at.reelloop.prototype

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode.MIRROR_MODE_ON_FRONT_ONLY
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private val Bg = Color(0xFF070A0F)
private val Panel = Color(0xFF10151D)
private val Panel2 = Color(0xFF141A23)
private val StrokeColor = Color(0xFF29313D)
private val Purple = Color(0xFF8A3FE7)
private val Red = Color(0xFFD83D4B)
private val Blue = Color(0xFF2F80E8)
private val Green = Color(0xFF64A33F)
private val Orange = Color(0xFFE98216)
private val Yellow = Color(0xFFF1C51B)
private val TrackColors = listOf(Purple, Blue, Green, Orange, Yellow, Purple)
private const val CLICK_OUTPUT_COMPENSATION_MS = 95L

enum class ScreenMode { SOLO, GRID, PIP, SPLIT }

data class TrackState(
    val index: Int,
    var uri: Uri? = null,
    var solo: Boolean = false,
    var mute: Boolean = false,
    var volume: Float = 0.8f,
    val clipStartMs: Long = 0L,
    val loopLengthMs: Long = 1L
)


private fun CompletableDeferred<Long>.getCompletedOrNull(): Long? =
    if (isCompleted && !isCancelled) runCatching { getCompleted() }.getOrNull() else null


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent { ReelLoopApp() }
    }
}

@Composable
private fun ReelLoopApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var permissionsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result[Manifest.permission.CAMERA] == true &&
                result[Manifest.permission.RECORD_AUDIO] == true
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    val tracks = remember {
        mutableStateListOf<TrackState>().apply {
            repeat(6) { add(TrackState(it)) }
        }
    }
    var selectedTrack by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(ScreenMode.GRID) }
    var bpm by remember { mutableIntStateOf(120) }
    var beatsPerBar by remember { mutableIntStateOf(4) }
    var beatUnit by remember { mutableIntStateOf(4) }
    var bars by remember { mutableIntStateOf(8) }
    var metronomeOn by remember { mutableStateOf(true) }
    var transportPlaying by remember { mutableStateOf(false) }
    var isCountingIn by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var currentBeat by remember { mutableIntStateOf(0) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var statusText by remember { mutableStateOf("Bereit") }

    val cameraController = remember { CameraRecordingController(context) }
    val masterClock = remember { MasterClock(scope) }
    val metronomeEngine = remember { MetronomeEngine(context) }
    var timingJob by remember { mutableStateOf<Job?>(null) }
    var metronomeJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(masterClock) {
        masterClock.elapsedMs.collect { elapsedMs = it }
    }
    val players = remember {
        List(6) { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE } }
    }
    DisposableEffect(Unit) {
        onDispose {
            players.forEach { it.release() }
            timingJob?.cancel()
            metronomeJob?.cancel()
            masterClock.stop()
            metronomeEngine.release()
            cameraController.release()
        }
    }

    fun syncPlayer(index: Int) {
        val track = tracks[index]
        val uri = track.uri ?: return
        val clipStart = track.clipStartMs.coerceAtLeast(0L)
        val clipEnd = (clipStart + track.loopLengthMs.coerceAtLeast(1L))
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clipStart)
                    .setEndPositionMs(clipEnd)
                    .build()
            )
            .build()
        players[index].setMediaItem(mediaItem)
        players[index].prepare()
        players[index].volume = if (track.mute) 0f else track.volume
    }

    fun totalBeats(): Long = bars.toLong() * beatsPerBar.toLong()

    fun loopDurationNs(): Long =
        ((totalBeats() * 60_000_000_000L) / bpm.toLong()).coerceAtLeast(1L)

    fun loopDurationMs(): Long =
        ((loopDurationNs() + 999_999L) / 1_000_000L).coerceAtLeast(1L)

    fun applyTrackVolumes() {
        val anySolo = tracks.any { it.solo }
        tracks.forEachIndexed { i, track ->
            val audible = track.uri != null && !track.mute && (!anySolo || track.solo)
            players[i].volume = if (audible) track.volume else 0f
        }
    }

    fun startPlayersAtZero(excludedTrack: Int? = null) {
        applyTrackVolumes()
        players.forEachIndexed { i, player ->
            if (i != excludedTrack && tracks[i].uri != null) {
                player.pause()
                player.seekTo(0)
            }
        }
        players.forEachIndexed { i, player ->
            if (i != excludedTrack && tracks[i].uri != null) player.play()
        }
    }

    fun stopAll() {
        timingJob?.cancel()
        timingJob = null
        metronomeJob?.cancel()
        metronomeJob = null
        masterClock.stop()
        players.forEach {
            it.pause()
            it.seekTo(0)
        }
        transportPlaying = false
        elapsedMs = 0
        currentBeat = 0
        statusText = "Bereit"
    }

    fun startMetronomeFrom(
        originNs: Long,
        referencePlayer: ExoPlayer? = null
    ) {
        metronomeJob?.cancel()
        if (!metronomeOn) return

        val beatDurationMs = 60_000.0 / bpm.toDouble()
        val outputCompensationMs = CLICK_OUTPUT_COMPENSATION_MS.toDouble()

        metronomeJob = scope.launch {
            if (referencePlayer == null) {
                var beatIndex = 0L
                while (isActive) {
                    val targetNs = originNs +
                        ((beatIndex * 60_000_000_000L) / bpm.toLong()) -
                        CLICK_OUTPUT_COMPENSATION_MS * 1_000_000L
                    masterClock.awaitUntil(targetNs)
                    val beatInBar = (beatIndex % beatsPerBar.toLong()).toInt()
                    currentBeat = beatInBar + 1
                    metronomeEngine.click(beatInBar == 0)
                    beatIndex++
                }
            } else {
                var lastLoopPosition = -1L
                var nextBeatInLoop = 0L
                val beatsInLoop = totalBeats().coerceAtLeast(1L)

                while (isActive) {
                    if (!referencePlayer.isPlaying) {
                        delay(2L)
                        continue
                    }

                    val position = referencePlayer.currentPosition.coerceAtLeast(0L)
                    if (lastLoopPosition >= 0L && position + 80L < lastLoopPosition) {
                        nextBeatInLoop = 0L
                    }
                    lastLoopPosition = position

                    while (nextBeatInLoop < beatsInLoop) {
                        val beatPositionMs =
                            nextBeatInLoop.toDouble() * beatDurationMs -
                                outputCompensationMs
                        if (position.toDouble() + 2.0 < beatPositionMs) break

                        val beatInBar =
                            (nextBeatInLoop % beatsPerBar.toLong()).toInt()
                        currentBeat = beatInBar + 1
                        metronomeEngine.click(beatInBar == 0)
                        nextBeatInLoop++
                    }
                    delay(2L)
                }
            }
        }
    }

    fun firstAudiblePlayer(excludedTrack: Int? = null): ExoPlayer? {
        players.forEachIndexed { index, player ->
            if (
                index != excludedTrack &&
                tracks[index].uri != null &&
                !tracks[index].mute
            ) return player
        }
        players.forEachIndexed { index, player ->
            if (index != excludedTrack && tracks[index].uri != null) return player
        }
        return null
    }

    fun playAll() {
        if (transportPlaying || isRecording || isCountingIn) return

        players.forEachIndexed { i, player ->
            if (tracks[i].uri != null) {
                player.pause()
                player.seekTo(0)
            }
        }

        val referencePlayer = firstAudiblePlayer()
        timingJob?.cancel()
        timingJob = scope.launch {
            startPlayersAtZero()

            if (referencePlayer != null) {
                withTimeoutOrNull(1_500L) {
                    while (!referencePlayer.isPlaying) delay(2L)
                }
                val originNs = masterClock.nowNs() -
                    referencePlayer.currentPosition * 1_000_000L
                masterClock.startAt(originNs, loopDurationNs())
                startMetronomeFrom(originNs, referencePlayer)
            } else {
                val originNs = masterClock.nowNs()
                masterClock.startAt(originNs, loopDurationNs())
                startMetronomeFrom(originNs, null)
            }
        }

        transportPlaying = true
        statusText = "Wiedergabe"
    }

    fun startCountInAndRecord() {
        if (!permissionsGranted || isRecording || isCountingIn) return

        val targetTrack = selectedTrack.coerceIn(0, 5)
        val recordingLoopNs = loopDurationNs()
        val recordingLoopMs = ((recordingLoopNs + 999_999L) / 1_000_000L)
        timingJob?.cancel()

        timingJob = scope.launch {
            isCountingIn = true
            transportPlaying = false
            statusText = "Einzählen"
            masterClock.stop()

            val countInStartNs = masterClock.nowNs() + 180_000_000L
            val countInDurationNs =
                (beatsPerBar.toLong() * 60_000_000_000L) / bpm.toLong()
            val recordingStartNs = countInStartNs + countInDurationNs
            val recordingEndNs = recordingStartNs + recordingLoopNs

            // CameraX needs startup time. It is started during the count-in.
            // The resulting preroll is removed later with Media3 clipping.
            val cameraLeadNs = minOf(
                1_200_000_000L,
                (countInDurationNs - 150_000_000L).coerceAtLeast(300_000_000L)
            )
            val cameraStartRequestNs = recordingStartNs - cameraLeadNs
            val cameraStartedAt = CompletableDeferred<Long>()
            val output = createVideoFile(context, targetTrack)

            val countInClicks = launch {
                for (beat in 0 until beatsPerBar) {
                    masterClock.awaitUntil(
                        countInStartNs +
                            (beat.toLong() * 60_000_000_000L) / bpm.toLong()
                    )
                    currentBeat = beat + 1
                    if (metronomeOn) metronomeEngine.click(beat == 0)
                }
            }

            masterClock.awaitUntil(cameraStartRequestNs)
            cameraController.startRecording(
                outputFile = output,
                lifecycleOwner = lifecycleOwner,
                onStarted = { startedAtNs ->
                    cameraStartedAt.complete(startedAtNs)
                },
                onFinalized = { uri ->
                    isRecording = false
                    val actualStartNs = cameraStartedAt.getCompletedOrNull()
                        ?: cameraStartRequestNs
                    val prerollMs = ((recordingStartNs - actualStartNs) / 1_000_000L +
                        CLICK_OUTPUT_COMPENSATION_MS)
                        .coerceAtLeast(0L)

                    tracks[targetTrack] = tracks[targetTrack].copy(
                        uri = uri,
                        clipStartMs = prerollMs,
                        loopLengthMs = recordingLoopMs
                    )
                    syncPlayer(targetTrack)
                    stopAll()
                    statusText = "Aufnahme gespeichert"
                },
                onError = {
                    if (!cameraStartedAt.isCompleted) {
                        cameraStartedAt.complete(cameraStartRequestNs)
                    }
                    isRecording = false
                    statusText = it
                    stopAll()
                }
            )

            countInClicks.join()
            masterClock.awaitUntil(recordingStartNs)

            startPlayersAtZero(excludedTrack = targetTrack)
            val referencePlayer = firstAudiblePlayer(excludedTrack = targetTrack)

            isCountingIn = false
            isRecording = true
            transportPlaying = true
            currentBeat = 1
            statusText = "Aufnahme"

            if (referencePlayer != null) {
                withTimeoutOrNull(1_000L) {
                    while (!referencePlayer.isPlaying) delay(2L)
                }
                val mediaOriginNs = masterClock.nowNs() -
                    referencePlayer.currentPosition * 1_000_000L
                masterClock.startAt(mediaOriginNs, recordingLoopNs)
                startMetronomeFrom(mediaOriginNs, referencePlayer)
            } else {
                masterClock.startAt(recordingStartNs, recordingLoopNs)
                startMetronomeFrom(recordingStartNs, null)
            }

            masterClock.awaitUntil(recordingEndNs)
            cameraController.stopRecording()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        val baseW = 691f
        val baseH = 1426f
        val scale = min(maxWidth.value / baseW, maxHeight.value / baseH)
        val contentW = (baseW * scale).dp
        val contentH = (baseH * scale).dp

        Box(
            modifier = Modifier
                .size(contentW, contentH)
                .align(Alignment.TopCenter)
        ) {
            Header(scale, mode, { mode = it })
            VideoScreen(
                scale = scale,
                mode = mode,
                tracks = tracks,
                players = players,
                selectedTrack = selectedTrack,
                permissionsGranted = permissionsGranted,
                cameraController = cameraController,
                lifecycleOwner = lifecycleOwner,
                onVolumeChange = { index, value ->
                    tracks[index] = tracks[index].copy(volume = value)
                    players[index].volume = if (tracks[index].mute) 0f else value
                }
            )
            Transport(
                scale = scale,
                playing = transportPlaying,
                recording = isRecording,
                counting = isCountingIn,
                metronome = metronomeOn,
                status = statusText,
                onPlay = { if (!transportPlaying) playAll() else Unit },
                onStop = {
                    if (isRecording) cameraController.stopRecording()
                    stopAll()
                },
                onRecord = { startCountInAndRecord() },
                onMetronome = { metronomeOn = !metronomeOn }
            )
            SettingsRow(
                scale = scale,
                bpm = bpm,
                bars = bars,
                beatsPerBar = beatsPerBar,
                beatUnit = beatUnit,
                onBpm = {
                    if (!transportPlaying && !isRecording && !isCountingIn) {
                        bpm = it.coerceIn(40, 240)
                    }
                },
                onBars = {
                    if (!transportPlaying && !isRecording && !isCountingIn) {
                        bars = it.coerceIn(1, 64)
                    }
                },
                onSignature = {
                    if (!transportPlaying && !isRecording && !isCountingIn) {
                        val signatures = listOf(4 to 4, 3 to 4, 6 to 8, 12 to 8, 5 to 4, 7 to 8)
                        val pos = signatures.indexOf(beatsPerBar to beatUnit)
                        val next = signatures[(pos + 1).mod(signatures.size)]
                        beatsPerBar = next.first
                        beatUnit = next.second
                    }
                }
            )
            Timeline(
                scale = scale,
                tracks = tracks,
                bars = bars,
                elapsedMs = elapsedMs,
                bpm = bpm,
                beatsPerBar = beatsPerBar,
                selectedTrack = selectedTrack,
                onSelectTrack = { selectedTrack = it },
                onSolo = { index ->
                    tracks[index] = tracks[index].copy(solo = !tracks[index].solo)
                },
                onMute = { index ->
                    tracks[index] = tracks[index].copy(mute = !tracks[index].mute)
                    players[index].volume =
                        if (tracks[index].mute) 0f else tracks[index].volume
                }
            )
        }
    }
}

@Composable
private fun Header(scale: Float, mode: ScreenMode, onMode: (ScreenMode) -> Unit) {
    Pos(0, 0, 691, 128, scale) {
        Box(Modifier.fillMaxSize().background(Color(0xFF080B10))) {
            Text("☰", color = Color.White, fontSize = (32 * scale).sp,
                modifier = Modifier.offset((20 * scale).dp, (15 * scale).dp))
            Text("ReelLoop", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = (28 * scale).sp,
                modifier = Modifier.offset((193 * scale).dp, (18 * scale).dp))
            Text("0.1.8", color = Color(0xFF9EA7B3), fontSize = (10 * scale).sp,
                modifier = Modifier.offset((322 * scale).dp, (31 * scale).dp))
            AppButton(466, 10, 48, 39, scale, "HD", null)
            AppButton(530, 7, 136, 45, scale, "⇧  EXPORT", Purple)

            val modes = listOf(
                Triple(ScreenMode.SOLO, "▯", "SOLO"),
                Triple(ScreenMode.GRID, "▦", "RASTER"),
                Triple(ScreenMode.PIP, "▣", "PIP"),
                Triple(ScreenMode.SPLIT, "▥", "SPLIT")
            )
            modes.forEachIndexed { i, item ->
                val x = 48 + i * 149
                val selected = mode == item.first
                Box(
                    Modifier
                        .offset((x * scale).dp, (64 * scale).dp)
                        .size((137 * scale).dp, (58 * scale).dp)
                        .clip(RoundedCornerShape((9 * scale).dp))
                        .background(Panel)
                        .border(
                            (if (selected) 1.5f else 1f).dp,
                            if (selected) Purple else StrokeColor,
                            RoundedCornerShape((9 * scale).dp)
                        )
                        .noRippleClick { onMode(item.first) }
                ) {
                    Text(item.second, color = Color(0xFFDCE2EA), fontSize = (27 * scale).sp,
                        modifier = Modifier.offset((30 * scale).dp, (12 * scale).dp))
                    Text(item.third, color = Color(0xFFDCE2EA), fontWeight = FontWeight.Bold,
                        fontSize = (14 * scale).sp,
                        modifier = Modifier.offset((70 * scale).dp, (20 * scale).dp))
                }
            }
        }
    }
}

@Composable
private fun TrackColumns(
    scale: Float,
    tracks: SnapshotStateList<TrackState>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onChange: (Int, TrackState) -> Unit
) {
    val positions = listOf(
        14 to 137, 14 to 386, 14 to 635,
        546 to 137, 546 to 386, 546 to 635
    )
    positions.forEachIndexed { i, pos ->
        val t = tracks[i]
        TrackPanel(
            x = pos.first, y = pos.second, scale = scale, track = t,
            selected = selected == i,
            onSelect = { onSelect(i) },
            onSolo = { onChange(i, t.copy(solo = !t.solo)) },
            onMute = { onChange(i, t.copy(mute = !t.mute)) },
            onVolume = { onChange(i, t.copy(volume = it)) }
        )
    }
}

@Composable
private fun TrackPanel(
    x: Int, y: Int, scale: Float, track: TrackState, selected: Boolean,
    onSelect: () -> Unit, onSolo: () -> Unit, onMute: () -> Unit, onVolume: (Float) -> Unit
) {
    val c = TrackColors[track.index]
    Pos(x, y, 122, 239, scale) {
        Box(
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape((11 * scale).dp))
                .background(Panel)
                .border((if (selected) 1.5f else 1f).dp, if (selected) c else StrokeColor,
                    RoundedCornerShape((11 * scale).dp))
                .noRippleClick(onSelect)
        ) {
            Box(Modifier.offset((10 * scale).dp, (10 * scale).dp)
                .size((23 * scale).dp, (23 * scale).dp)
                .clip(RoundedCornerShape((3 * scale).dp)).background(c)) {
                Text("${track.index + 1}", color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = (13 * scale).sp, modifier = Modifier.align(Alignment.Center))
            }
            Text("SPUR ${track.index + 1}", color = c, fontWeight = FontWeight.Bold,
                fontSize = (13 * scale).sp,
                modifier = Modifier.offset((43 * scale).dp, (13 * scale).dp))
            SmallToggle(10, 48, 44, 38, scale, "S", track.solo, c, onSolo)
            SmallToggle(64, 48, 44, 38, scale, "M", track.mute, c, onMute)
            Box(
                Modifier.offset((49 * scale).dp, (102 * scale).dp)
                    .size((24 * scale).dp, (95 * scale).dp)
                    .pointerInput(track.volume) {
                        detectVerticalDragGestures { _, drag ->
                            onVolume((track.volume - drag / 230f).coerceIn(0f, 1f))
                        }
                    }
            ) {
                Box(Modifier.align(Alignment.Center).width((6 * scale).dp).height((76 * scale).dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color(0xFF1B222D)))
                Box(Modifier.align(Alignment.BottomCenter).width((6 * scale).dp)
                    .height((76 * track.volume * scale).dp)
                    .clip(RoundedCornerShape(4.dp)).background(c))
                Box(Modifier.align(Alignment.BottomCenter)
                    .offset(y = (-(76 * track.volume * scale) + 5 * scale).dp)
                    .size((16 * scale).dp).clip(RoundedCornerShape(50)).background(Color.White))
            }
            Text("${(track.volume * 100).toInt()}%", color = Color(0xFFC8CFDA),
                fontSize = (13 * scale).sp,
                modifier = Modifier.offset((42 * scale).dp, (197 * scale).dp))
            Text("•••", color = Color(0xFFADB5C1), letterSpacing = (2 * scale).sp,
                fontSize = (14 * scale).sp,
                modifier = Modifier.offset((48 * scale).dp, (216 * scale).dp))
        }
    }
}

@Composable
private fun VideoScreen(
    scale: Float,
    mode: ScreenMode,
    tracks: List<TrackState>,
    players: List<ExoPlayer>,
    selectedTrack: Int,
    permissionsGranted: Boolean,
    cameraController: CameraRecordingController,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onVolumeChange: (Int, Float) -> Unit
) {
    // 438 × 780 is effectively 9:16 and uses the space freed by the removed side panels.
    val screenWidth = 438
    val screenHeight = 780

    Pos(126, 132, screenWidth, screenHeight, scale) {
        Box(
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape((10 * scale).dp))
                .background(Color.Black)
                .border(1.dp, Purple, RoundedCornerShape((10 * scale).dp))
        ) {
            when (mode) {
                ScreenMode.SOLO -> {
                    ClipCell(
                        0, 0, screenWidth, screenHeight, scale, selectedTrack,
                        tracks, players, permissionsGranted,
                        cameraController, lifecycleOwner, onVolumeChange
                    )
                }

                ScreenMode.GRID -> {
                    // Four mathematically identical 219 × 390 cells.
                    val visible = listOf(0, 1, 2, 3)
                    val previewIndex = when {
                        selectedTrack in visible -> selectedTrack
                        else -> visible.firstOrNull { tracks[it].uri == null } ?: -1
                    }

                    visible.forEachIndexed { cell, trackIndex ->
                        val cx = if (cell % 2 == 0) 0 else 219
                        val cy = if (cell < 2) 0 else 390
                        ClipCell(
                            cx, cy, 219, 390, scale, trackIndex,
                            tracks, players,
                            permissionsGranted && trackIndex == previewIndex,
                            cameraController, lifecycleOwner, onVolumeChange
                        )
                    }
                }

                ScreenMode.SPLIT -> {
                    val visible = listOf(0, 1)
                    val previewIndex = when {
                        selectedTrack in visible -> selectedTrack
                        else -> visible.firstOrNull { tracks[it].uri == null } ?: -1
                    }

                    ClipCell(
                        0, 0, 219, screenHeight, scale, 0,
                        tracks, players, permissionsGranted && previewIndex == 0,
                        cameraController, lifecycleOwner, onVolumeChange
                    )
                    ClipCell(
                        219, 0, 219, screenHeight, scale, 1,
                        tracks, players, permissionsGranted && previewIndex == 1,
                        cameraController, lifecycleOwner, onVolumeChange
                    )
                }

                ScreenMode.PIP -> {
                    ClipCell(
                        0, 0, screenWidth, screenHeight, scale, selectedTrack,
                        tracks, players, permissionsGranted,
                        cameraController, lifecycleOwner, onVolumeChange
                    )

                    val pipTrack = (selectedTrack + 1) % 6
                    Box(
                        Modifier.offset((278 * scale).dp, (18 * scale).dp)
                            .size((142 * scale).dp, (252 * scale).dp)
                            .background(Color.Black)
                            .border(2.dp, TrackColors[pipTrack])
                    ) {
                        ClipCell(
                            0, 0, 142, 252, scale, pipTrack,
                            tracks, players, false,
                            cameraController, lifecycleOwner, onVolumeChange
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipCell(
    x: Int, y: Int, w: Int, h: Int, scale: Float, index: Int,
    tracks: List<TrackState>, players: List<ExoPlayer>, permissionsGranted: Boolean,
    cameraController: CameraRecordingController,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onVolumeChange: (Int, Float) -> Unit
) {
    val track = tracks[index]
    Box(
        Modifier.offset((x * scale).dp, (y * scale).dp)
            .size((w * scale).dp, (h * scale).dp)
            .background(Color.Black)
            .border(0.7.dp, TrackColors[index])
            .pointerInput(track.volume) {
                detectVerticalDragGestures { _, drag ->
                    onVolumeChange(index, (track.volume - drag / 300f).coerceIn(0f, 1f))
                }
            }
    ) {
        if (track.uri != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        player = players[index]
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { it.player = players[index] }
            )
        } else if (permissionsGranted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).also {
                        it.scaleType = PreviewView.ScaleType.FILL_CENTER
                        cameraController.bindPreview(it, lifecycleOwner)
                    }
                }
            )
        }
        Box(Modifier.offset((9 * scale).dp, (9 * scale).dp)
            .size((23 * scale).dp).clip(RoundedCornerShape(3.dp))
            .background(TrackColors[index])) {
            Text("${index + 1}", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = (13 * scale).sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun Transport(
    scale: Float, playing: Boolean, recording: Boolean, counting: Boolean,
    metronome: Boolean, status: String,
    onPlay: () -> Unit, onStop: () -> Unit, onRecord: () -> Unit, onMetronome: () -> Unit
) {
    Pos(23, 923, 645, 52, scale) {
        Box(Modifier.fillMaxSize()) {
            TransportButton(0, 0, 147, 48, scale, "▶  PLAY", if (playing) Blue else null, onPlay)
            TransportButton(157, 0, 147, 48, scale, "■  STOP", null, onStop)
            TransportButton(
                314, 0, 147, 48, scale,
                if (recording) "●  REC" else if (counting) "●  EINZÄHLEN" else "●  REC",
                Red, onRecord
            )
            TransportButton(
                471, 0, 174, 48, scale,
                "♪  METRONOM  ${if (metronome) "ON" else "OFF"}",
                if (metronome) Purple else null, onMetronome
            )
        }
    }
}

@Composable
private fun SettingsRow(
    scale: Float, bpm: Int, bars: Int, beatsPerBar: Int, beatUnit: Int,
    onBpm: (Int) -> Unit, onBars: (Int) -> Unit, onSignature: () -> Unit
) {
    var lastTapMs by remember { mutableLongStateOf(0L) }

    Pos(23, 984, 645, 78, scale) {
        Box(
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape((11 * scale).dp))
                .background(Panel)
                .border(1.dp, StrokeColor, RoundedCornerShape((11 * scale).dp))
        ) {
            SettingCell(0, 0, 132, 78, scale, "◉ EINZÄHLEN", "1 TAKT", Purple, {})

            SettingCell(
                132, 0, 142, 78, scale,
                "TAKTART", "$beatsPerBar/$beatUnit ⌄", Color.White, onSignature
            )

            SettingCell(
                274, 0, 132, 78, scale,
                "TAKTE", "$bars     ⌄", Color.White
            ) {
                val values = listOf(1, 2, 4, 8, 16, 32, 64)
                val current = values.indexOf(bars)
                onBars(values[(current + 1).mod(values.size)])
            }

            Box(
                Modifier.offset((406 * scale).dp, 0.dp)
                    .size((257 * scale).dp, (78 * scale).dp)
            ) {
                Text(
                    "TEMPO", color = Color(0xFF9AA5B4),
                    fontSize = (11 * scale).sp,
                    modifier = Modifier.offset((17 * scale).dp, (13 * scale).dp)
                )
                Text(
                    "$bpm", color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (15 * scale).sp,
                    modifier = Modifier.offset((17 * scale).dp, (34 * scale).dp)
                )
                Text(
                    "BPM", color = Color(0xFF9AA5B4),
                    fontSize = (10 * scale).sp,
                    modifier = Modifier.offset((17 * scale).dp, (53 * scale).dp)
                )

                Box(
                    Modifier.offset((68 * scale).dp, (18 * scale).dp)
                        .size((110 * scale).dp, (44 * scale).dp)
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val fraction =
                                    (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                onBpm(40 + (fraction * 200f).toInt())
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                val fraction =
                                    (change.position.x / size.width.toFloat())
                                        .coerceIn(0f, 1f)
                                onBpm(40 + (fraction * 200f).toInt())
                                change.consume()
                            }
                        }
                ) {
                    Box(
                        Modifier.align(Alignment.Center)
                            .size((104 * scale).dp, (8 * scale).dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFF2A3240))
                    ) {
                        Box(
                            Modifier.fillMaxHeight()
                                .fillMaxWidth(((bpm - 40) / 200f).coerceIn(0f, 1f))
                                .background(Purple)
                        )
                    }
                }

                SmallToggle(181, 20, 59, 39, scale, "TAP", false, Purple) {
                    val now = SystemClock.elapsedRealtime()
                    if (lastTapMs > 0L) {
                        val interval = now - lastTapMs
                        if (interval in 250L..1500L) {
                            onBpm((60_000L / interval).toInt())
                        }
                    }
                    lastTapMs = now
                }
            }
        }
    }
}

@Composable
private fun Timeline(
    scale: Float,
    tracks: List<TrackState>,
    bars: Int,
    elapsedMs: Long,
    bpm: Int,
    beatsPerBar: Int,
    selectedTrack: Int,
    onSelectTrack: (Int) -> Unit,
    onSolo: (Int) -> Unit,
    onMute: (Int) -> Unit
) {
    Pos(23, 1074, 645, 341, scale) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape((11 * scale).dp))
            .background(Panel).border(1.dp, StrokeColor, RoundedCornerShape((11 * scale).dp))) {
            Text("00:${(elapsedMs / 1000).toString().padStart(2, '0')}:${((elapsedMs % 1000) / 10).toString().padStart(2, '0')}",
                color = Color(0xFF91A0B1), fontSize = (12 * scale).sp,
                modifier = Modifier.offset((18 * scale).dp, (8 * scale).dp))
            val rowH = 47
            tracks.forEachIndexed { i, track ->
                val y = 52 + i * rowH
                Box(Modifier.offset(0.dp, (y * scale).dp)
                    .size((170 * scale).dp, (rowH * scale).dp)
                    .background(if (selectedTrack == i) TrackColors[i].copy(alpha = 0.08f) else Color.Transparent)
                    .border(
                        if (selectedTrack == i) 1.dp else 0.5.dp,
                        if (selectedTrack == i) TrackColors[i] else StrokeColor
                    )
                    .noRippleClick { onSelectTrack(i) }) {
                    Box(Modifier.offset((7 * scale).dp, (12 * scale).dp)
                        .size((23 * scale).dp).clip(RoundedCornerShape(3.dp))
                        .background(TrackColors[i])) {
                        Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold,
                            fontSize = (12 * scale).sp, modifier = Modifier.align(Alignment.Center))
                    }
                    Text("SPUR ${i + 1}", color = Color.White, fontSize = (11 * scale).sp,
                        modifier = Modifier.offset((39 * scale).dp, (15 * scale).dp))
                    Box(
                        Modifier.offset((101 * scale).dp, (7 * scale).dp)
                            .size((31 * scale).dp, (32 * scale).dp)
                            .clip(RoundedCornerShape((5 * scale).dp))
                            .background(if (track.solo) TrackColors[i] else Panel2)
                            .noRippleClick { onSolo(i) }
                    ) {
                        Text("S", color = Color.White, fontWeight = FontWeight.Bold,
                            fontSize = (11 * scale).sp, modifier = Modifier.align(Alignment.Center))
                    }
                    Box(
                        Modifier.offset((136 * scale).dp, (7 * scale).dp)
                            .size((31 * scale).dp, (32 * scale).dp)
                            .clip(RoundedCornerShape((5 * scale).dp))
                            .background(if (track.mute) TrackColors[i] else Panel2)
                            .noRippleClick { onMute(i) }
                    ) {
                        Text("M", color = Color.White, fontWeight = FontWeight.Bold,
                            fontSize = (11 * scale).sp, modifier = Modifier.align(Alignment.Center))
                    }
                }
                if (track.uri != null) {
                    Waveform(176, y + 4, 390, 39, scale, TrackColors[i], bars)
                }
            }
            val loopMs = (bars * beatsPerBar * 60_000L / bpm).coerceAtLeast(1)
            val progress = (elapsedMs % loopMs).toFloat() / loopMs
            val px = 176 + progress * 470
            Box(Modifier.offset((px * scale).dp, (43 * scale).dp)
                .size((2 * scale).dp, (293 * scale).dp).background(Color(0xFFD09BFF)))
        }
    }
}

@Composable
private fun Waveform(x: Int, y: Int, w: Int, h: Int, scale: Float, color: Color, bars: Int) {
    Canvas(
        Modifier.offset((x * scale).dp, (y * scale).dp)
            .size((w * scale).dp, (h * scale).dp)
            .background(color.copy(alpha = 0.20f))
            .border(1.dp, color, RoundedCornerShape(2.dp))
    ) {
        val seed = (x + y + bars)
        val random = Random(seed)
        val p = Path()
        val mid = size.height / 2
        p.moveTo(0f, mid)
        var px = 0f
        while (px <= size.width) {
            val amp = random.nextFloat() * size.height * .42f
            p.lineTo(px, mid - amp)
            p.lineTo(px, mid + amp)
            px += max(2f, size.width / 100f)
        }
        drawPath(p, color.copy(alpha = .75f), style = Stroke(width = 1.2f))
    }
}

class CameraRecordingController(private val context: Context) {
    private var provider: ProcessCameraProvider? = null
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var previewView: PreviewView? = null

    fun bindPreview(view: PreviewView, owner: androidx.lifecycle.LifecycleOwner) {
        if (previewView === view && provider != null) return
        previewView = view
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(view.surfaceProvider)
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            capture = VideoCapture.Builder(recorder)
                .setMirrorMode(MIRROR_MODE_ON_FRONT_ONLY)
                .build()
            try {
                provider?.unbindAll()
                provider?.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    capture
                )
            } catch (_: Exception) { }
        }, ContextCompat.getMainExecutor(context))
    }

    fun startRecording(
        outputFile: File,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        onStarted: (Long) -> Unit,
        onFinalized: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        val videoCapture = capture ?: run {
            onError("Kamera noch nicht bereit")
            return
        }
        var pending: PendingRecording = videoCapture.output
            .prepareRecording(context, FileOutputOptions.Builder(outputFile).build())
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    onStarted(SystemClock.elapsedRealtimeNanos())
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    if (event.hasError()) {
                        onError("Aufnahmefehler ${event.error}")
                    } else {
                        onFinalized(Uri.fromFile(outputFile))
                    }
                }
            }
        }
    }

    fun stopRecording() {
        recording?.stop()
    }

    fun release() {
        recording?.close()
        provider?.unbindAll()
    }
}

private fun createVideoFile(context: Context, track: Int): File {
    val dir = File(context.filesDir, "reelloop_tracks").apply { mkdirs() }
    return File(dir, "track_${track + 1}_${System.currentTimeMillis()}.mp4")
}

@Composable
private fun Pos(x: Int, y: Int, w: Int, h: Int, scale: Float, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.offset((x * scale).dp, (y * scale).dp)
            .size((w * scale).dp, (h * scale).dp),
        content = content
    )
}

@Composable
private fun AppButton(x: Int, y: Int, w: Int, h: Int, scale: Float, text: String, fill: Color?) {
    Box(Modifier.offset((x * scale).dp, (y * scale).dp)
        .size((w * scale).dp, (h * scale).dp)
        .clip(RoundedCornerShape((7 * scale).dp))
        .background(fill ?: Panel)
        .border(1.dp, if (fill == null) Color(0xFF8C96A4) else fill,
            RoundedCornerShape((7 * scale).dp))) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            fontSize = (13 * scale).sp, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun SmallToggle(
    x: Int, y: Int, w: Int, h: Int, scale: Float, text: String,
    active: Boolean, activeColor: Color, onClick: () -> Unit
) {
    Box(Modifier.offset((x * scale).dp, (y * scale).dp)
        .size((w * scale).dp, (h * scale).dp)
        .clip(RoundedCornerShape((7 * scale).dp))
        .background(if (active) activeColor else Panel2)
        .border(1.dp, if (active) activeColor else StrokeColor, RoundedCornerShape((7 * scale).dp))
        .noRippleClick(onClick)) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = (13 * scale).sp, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun TransportButton(
    x: Int, y: Int, w: Int, h: Int, scale: Float, text: String, fill: Color?, onClick: () -> Unit
) {
    Box(Modifier.offset((x * scale).dp, (y * scale).dp)
        .size((w * scale).dp, (h * scale).dp)
        .clip(RoundedCornerShape((11 * scale).dp))
        .background(fill?.copy(alpha = .55f) ?: Panel)
        .border(1.dp, fill ?: StrokeColor, RoundedCornerShape((11 * scale).dp))
        .noRippleClick(onClick)) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            fontSize = (13 * scale).sp, lineHeight = (18 * scale).sp,
            modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun SettingCell(
    x: Int, y: Int, w: Int, h: Int, scale: Float,
    title: String, value: String, accent: Color, onClick: () -> Unit
) {
    Box(Modifier.offset((x * scale).dp, (y * scale).dp)
        .size((w * scale).dp, (h * scale).dp)
        .border(0.5.dp, StrokeColor)
        .noRippleClick(onClick)) {
        Text(title, color = if (accent == Color.White) Color(0xFF9AA5B4) else accent,
            fontWeight = FontWeight.Bold, fontSize = (10 * scale).sp,
            modifier = Modifier.offset((15 * scale).dp, (13 * scale).dp))
        Text(value, color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = (14 * scale).sp,
            modifier = Modifier.offset((15 * scale).dp, (42 * scale).dp))
    }
}

private fun Modifier.noRippleClick(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick
    )

