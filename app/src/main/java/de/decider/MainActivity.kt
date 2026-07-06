package de.decider

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OracleScreen()
                }
            }
        }
    }
}

/**
 * Physical entropy source in the spirit of a GCP "egg": harvests camera
 * sensor noise (dark-frame noise works best — covering the lens is fine),
 * XOR-whitens the per-pixel LSBs and folds them into a SHA-256 entropy
 * pool. [drainSeed] hands out conditioned 32-byte seeds for reseeding the
 * sampling RNGs, so fresh physical entropy keeps flowing into the
 * bitstream for the whole run.
 */
class CameraEntropy(private val context: Context) {
    val frames = AtomicInteger(0)

    private val lock = Any()
    private var pool = ByteArray(32)
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null

    private fun sha(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: mgr.cameraIdList.firstOrNull() ?: return
            thread = HandlerThread("entropy-cam").apply { start() }
            val handler = Handler(thread!!.looper)
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        harvest(img)
                    } finally {
                        img.close()
                    }
                }, handler)
            }
            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    val surface = imageReader?.surface ?: return
                    device.createCaptureSession(
                        listOf(surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    .apply { addTarget(surface) }
                                s.setRepeatingRequest(req.build(), null, handler)
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {}
                        },
                        handler
                    )
                }

                override fun onDisconnected(device: CameraDevice) = device.close()
                override fun onError(device: CameraDevice, error: Int) = device.close()
            }, handler)
        } catch (_: Exception) {
            // No camera / camera busy: the run silently falls back to PRNG-only.
        }
    }

    private fun harvest(img: Image) {
        val buf = img.planes[0].buffer // luminance plane, noise-rich LSBs
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        // Pack the LSB of 8 pixels into one byte, XOR-whitened with the
        // alternating 0101 mask GCP eggs use to cancel first-order bias.
        val packed = ByteArray(bytes.size / 8)
        for (i in packed.indices) {
            var b = 0
            for (j in 0 until 8) b = (b shl 1) or (bytes[i * 8 + j].toInt() and 1)
            packed[i] = (b xor 0x55).toByte()
        }
        synchronized(lock) { pool = sha(pool, packed) }
        frames.incrementAndGet()
    }

    /** Conditioned 32-byte seed; ratchets the pool so seeds never repeat. */
    fun drainSeed(): ByteArray = synchronized(lock) {
        pool = sha(pool, System.nanoTime().toString().toByteArray())
        val out = sha(pool, byteArrayOf(1))
        pool = sha(pool, byteArrayOf(2))
        out
    }

    fun stop() {
        try {
            session?.close()
            cameraDevice?.close()
            imageReader?.close()
        } catch (_: Exception) {
        }
        thread?.quitSafely()
        session = null
        cameraDevice = null
        imageReader = null
        thread = null
    }
}

/** Result of one GCP run. [completed] is false if the user stopped it early. */
data class GcpResult(
    val yes: Boolean,
    val ones: Long,
    val totalBits: Long,
    val zScore: Double,
    val pValue: Double,
    val elapsedMs: Long,
    val completed: Boolean,
    val cameraFrames: Int // -1 = no camera entropy was used
)

/** Counts set bits in the first [len] bytes of [buffer], 8 bytes at a time via Long.bitCount. */
private fun countOnes(buffer: ByteArray, len: Int): Long {
    var ones = 0L
    val longBytes = (len / 8) * 8
    if (longBytes > 0) {
        val bb = ByteBuffer.wrap(buffer, 0, longBytes)
        while (bb.hasRemaining()) {
            ones += java.lang.Long.bitCount(bb.long)
        }
    }
    for (i in longBytes until len) {
        ones += Integer.bitCount(buffer[i].toInt() and 0xFF)
    }
    return ones
}

/** Two-sided p-value for a z-score (erfc via Abramowitz–Stegun 7.1.26). */
fun pTwoSided(z: Double): Double {
    val x = abs(z) / sqrt(2.0)
    val t = 1.0 / (1.0 + 0.3275911 * x)
    val erf = 1.0 - (((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t -
        0.284496736) * t + 0.254829592) * t) * exp(-x * x)
    return (1.0 - erf).coerceIn(0.0, 1.0)
}

fun strengthLabel(z: Double): String = when {
    abs(z) < 1.0 -> "coin-flip territory"
    abs(z) < 1.96 -> "weak lean"
    abs(z) < 3.0 -> "notable deviation"
    else -> "strong deviation"
}

/**
 * GCP mechanism: draw [totalBits] random bits, count the ones.
 * More ones than expected (N/2) => YES, fewer => NO; an exact tie is
 * decided by one extra bit.
 *
 * The work is split into 1 MiB chunks distributed across one worker per
 * CPU core, each with its own SecureRandom. When [entropy] is present,
 * every chunk reseeds its RNG with fresh camera-noise entropy
 * (SecureRandom.setSeed supplements state, so this can only add
 * randomness, never weaken it). Progress is reported via [onUpdate]
 * roughly ten times per second; if [isStopped] starts returning true the
 * run ends early and the result reflects the bits sampled so far.
 */
suspend fun runGcp(
    totalBits: Long,
    entropy: CameraEntropy?,
    onUpdate: (bitsDone: Long, ones: Long) -> Unit,
    isStopped: () -> Boolean
): GcpResult = withContext(Dispatchers.Default) {
    val startTime = System.nanoTime()
    val chunkBytes = 1 shl 20
    val totalBytes = totalBits / 8
    val totalChunks = (totalBytes + chunkBytes - 1) / chunkBytes
    val nextChunk = AtomicLong(0)
    val onesTotal = AtomicLong(0)
    val bytesDone = AtomicLong(0)
    val stopped = AtomicBoolean(false)
    val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    val jobs = List(workers) {
        launch {
            val rng = SecureRandom()
            val buffer = ByteArray(chunkBytes)
            while (!stopped.get()) {
                val idx = nextChunk.getAndIncrement()
                if (idx >= totalChunks) break
                if (isStopped()) {
                    stopped.set(true)
                    break
                }
                entropy?.let { rng.setSeed(it.drainSeed()) }
                val n = minOf(chunkBytes.toLong(), totalBytes - idx * chunkBytes).toInt()
                rng.nextBytes(buffer)
                onesTotal.addAndGet(countOnes(buffer, n))
                bytesDone.addAndGet(n.toLong())
                yield()
            }
        }
    }

    while (jobs.any { it.isActive }) {
        onUpdate(bytesDone.get() * 8, onesTotal.get())
        delay(100)
    }
    jobs.forEach { it.join() }

    val actualBits = bytesDone.get() * 8
    val ones = onesTotal.get()
    val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
    val camFrames = entropy?.frames?.get() ?: -1
    onUpdate(actualBits, ones)

    if (actualBits == 0L) {
        return@withContext GcpResult(
            SecureRandom().nextInt(2) == 1, 0, 0, 0.0, 1.0,
            elapsedMs, !stopped.get(), camFrames
        )
    }

    val expected = actualBits / 2.0
    // Standard deviation of a binomial(N, 0.5) distribution: sqrt(N)/2
    val z = (ones - expected) / (sqrt(actualBits.toDouble()) / 2.0)
    val yes = when {
        ones * 2 > actualBits -> true
        ones * 2 < actualBits -> false
        else -> (SecureRandom().nextInt(2) == 1) // exact tie: one extra bit decides
    }
    GcpResult(yes, ones, actualBits, z, pTwoSided(z), elapsedMs, !stopped.get(), camFrames)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OracleScreen() {
    var question by remember { mutableStateOf("") }
    var selectedBits by remember { mutableStateOf(10_000_000_000L) }
    var running by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<GcpResult?>(null) }
    var runStartMs by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(0L) }
    var camFrames by remember { mutableStateOf(-1) }
    var camEntropy by remember { mutableStateOf<CameraEntropy?>(null) }
    val zHistory = remember { mutableStateListOf<Float>() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose { camEntropy?.stop() }
    }

    LaunchedEffect(running) {
        while (running) {
            nowMs = System.currentTimeMillis()
            delay(200)
        }
    }

    val startRun: (Boolean) -> Unit = { useCamera ->
        result = null
        progress = 0f
        stopRequested = false
        zHistory.clear()
        camFrames = -1
        runStartMs = System.currentTimeMillis()
        nowMs = runStartMs
        running = true
        val entropy = if (useCamera) CameraEntropy(context).also { it.start() } else null
        camEntropy = entropy
        scope.launch {
            val r = runGcp(
                selectedBits,
                entropy,
                onUpdate = { bits, ones ->
                    // MutableState writes are safe from any thread
                    if (selectedBits > 0) progress = bits.toFloat() / selectedBits
                    camFrames = entropy?.frames?.get() ?: -1
                    if (bits > 0) {
                        val z = (ones - bits / 2.0) / (sqrt(bits.toDouble()) / 2.0)
                        zHistory.add(z.toFloat())
                        if (zHistory.size > 300) {
                            val thinned = zHistory.filterIndexed { i, _ -> i % 2 == 0 }
                            zHistory.clear()
                            zHistory.addAll(thinned)
                        }
                    }
                },
                isStopped = { stopRequested }
            )
            entropy?.stop()
            camEntropy = null
            result = r
            running = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> startRun(granted) }

    val options = listOf(
        1_000_000_000L to "1G",
        10_000_000_000L to "10G",
        50_000_000_000L to "50G",
        100_000_000_000L to "100G"
    )

    val lightBlue = Color(0xFFADD8E6)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Decider", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Using the GCP principles",
            color = Color(0xFF64B5F6),
            textDecoration = TextDecoration.Underline,
            fontSize = 14.sp,
            modifier = Modifier.clickable {
                uriHandler.openUri("https://grokipedia.com/page/Global_Consciousness_Project")
            }
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            label = { Text("Your yes/no question") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            minLines = 2
        )

        Spacer(Modifier.height(16.dp))
        Text("Random bits to sample", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (bits, label) ->
                val isSelected = selectedBits == bits
                FilterChip(
                    selected = isSelected,
                    onClick = { if (!running) selectedBits = bits },
                    label = {
                        Text(
                            label,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    enabled = !running,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.White,
                        labelColor = Color.Black,
                        selectedContainerColor = lightBlue,
                        selectedLabelColor = Color.Black,
                        disabledContainerColor = Color.White,
                        disabledLabelColor = Color.Black
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = !running,
                        selected = isSelected,
                        borderColor = Color.Black,
                        selectedBorderColor = Color.Black,
                        borderWidth = 1.dp,
                        selectedBorderWidth = 2.dp
                    )
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (running) {
                    stopRequested = true
                } else if (context.checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    startRun(true)
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            enabled = if (running) !stopRequested else question.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                when {
                    running && stopRequested -> "Stopping…"
                    running -> "Consulting… Stop any time"
                    else -> "Ask the Oracle"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }

        Spacer(Modifier.height(32.dp))

        when {
            running -> {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("${(progress * 100).toInt()} %")

                val elapsed = nowMs - runStartMs
                if (progress > 0f) {
                    val estimatedTotal = elapsed / progress
                    val remaining = estimatedTotal - elapsed
                    if (estimatedTotal > 10_000 && remaining > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text("Estimated time remaining: ${"%.0f".format(remaining / 1000.0)} s")
                    }
                }

                if (camFrames >= 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "📷 Camera noise mixed in: $camFrames frames " +
                            "(covering the lens is fine — dark-frame noise is ideal)",
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                }

                if (zHistory.size >= 2) {
                    Spacer(Modifier.height(16.dp))
                    ZWalkChart(zHistory.toList(), lightBlue)
                    Text(
                        "cumulative z random walk",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            result != null -> {
                val r = result!!
                Text(
                    text = if (r.yes) "👍" else "👎",
                    fontSize = 140.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (r.yes) "YES" else "NO",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))
                if (!r.completed) {
                    Text(
                        "(stopped early)",
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (zHistory.size >= 2) {
                    ZWalkChart(zHistory.toList(), lightBlue)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "Ones: ${r.ones} of ${r.totalBits} bits\n" +
                        "Deviation: ${"%+d".format(r.ones - r.totalBits / 2)}   " +
                        "z = ${"%.3f".format(r.zScore)}\n" +
                        "p = ${"%.3f".format(r.pValue)} (${strengthLabel(r.zScore)})\n" +
                        "Entropy: ${
                            if (r.cameraFrames >= 0) "PRNG + camera noise (${r.cameraFrames} frames)"
                            else "PRNG only"
                        }\n" +
                        "Elapsed: ${"%.2f".format(r.elapsedMs / 1000.0)} s",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * The GCP's signature visualization: the running z-score drifting as a
 * random walk, with the ±1.96 significance band as dashed guides.
 */
@Composable
fun ZWalkChart(points: List<Float>, lineColor: Color) {
    val guideColor = Color.Gray
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
    ) {
        val maxAbs = max(2.5f, points.maxOf { abs(it) } * 1.1f)
        fun yOf(z: Float) = size.height / 2 - (z / maxAbs) * (size.height / 2)

        drawLine(guideColor, Offset(0f, yOf(0f)), Offset(size.width, yOf(0f)), 1.dp.toPx())
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        drawLine(guideColor, Offset(0f, yOf(1.96f)), Offset(size.width, yOf(1.96f)), 1.dp.toPx(), pathEffect = dash)
        drawLine(guideColor, Offset(0f, yOf(-1.96f)), Offset(size.width, yOf(-1.96f)), 1.dp.toPx(), pathEffect = dash)

        val path = Path()
        points.forEachIndexed { i, z ->
            val x = i * size.width / (points.size - 1)
            if (i == 0) path.moveTo(x, yOf(z)) else path.lineTo(x, yOf(z))
        }
        drawPath(path, lineColor, style = Stroke(2.dp.toPx()))
    }
}
