package de.decider

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.math.abs
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

/** Result of one GCP run. [completed] is false if the user stopped it early. */
data class GcpResult(
    val yes: Boolean,
    val ones: Long,
    val totalBits: Long,
    val zScore: Double,
    val elapsedMs: Long,
    val completed: Boolean
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

/**
 * GCP mechanism: draw [totalBits] random bits from a cryptographic RNG,
 * count the ones. More ones than expected (N/2) => YES, fewer => NO.
 * On an exact tie, one extra bit decides.
 *
 * Works in 1 MiB chunks and reports progress via [onProgress] (0f..1f).
 * If [isStopped] starts returning true, the run ends early and the result
 * reflects only the bits actually sampled so far.
 */
suspend fun runGcp(totalBits: Long, onProgress: (Float) -> Unit, isStopped: () -> Boolean): GcpResult {
    val startTime = System.nanoTime()
    val rng = SecureRandom()
    val chunkBytes = 1 * 1024 * 1024
    val buffer = ByteArray(chunkBytes)
    val totalBytes = totalBits / 8
    var bytesDone = 0L
    var ones = 0L
    var lastReported = -1
    var stoppedEarly = false

    while (bytesDone < totalBytes) {
        if (isStopped()) {
            stoppedEarly = true
            break
        }
        val n = minOf(chunkBytes.toLong(), totalBytes - bytesDone).toInt()
        if (n < chunkBytes) {
            val small = ByteArray(n)
            rng.nextBytes(small)
            ones += countOnes(small, n)
        } else {
            rng.nextBytes(buffer)
            ones += countOnes(buffer, n)
        }
        bytesDone += n

        // Only push a progress update when the percentage actually changed,
        // to avoid flooding the UI thread.
        val pct = ((bytesDone * 100) / totalBytes).toInt()
        if (pct != lastReported) {
            lastReported = pct
            onProgress(pct / 100f)
        }
        // Cooperate with cancellation (user could rotate device etc.)
        kotlinx.coroutines.yield()
    }

    val actualTotalBits = bytesDone * 8
    val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

    if (actualTotalBits == 0L) {
        return GcpResult(rng.nextInt(2) == 1, 0, 0, 0.0, elapsedMs, !stoppedEarly)
    }

    val expected = actualTotalBits / 2.0
    // Standard deviation of a binomial(N, 0.5) distribution: sqrt(N)/2
    val z = (ones - expected) / (sqrt(actualTotalBits.toDouble()) / 2.0)

    val yes = when {
        ones * 2 > actualTotalBits -> true
        ones * 2 < actualTotalBits -> false
        else -> (rng.nextInt(2) == 1) // exact tie: one extra bit decides
    }
    return GcpResult(yes, ones, actualTotalBits, z, elapsedMs, !stoppedEarly)
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
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(running) {
        while (running) {
            nowMs = System.currentTimeMillis()
            delay(200)
        }
    }

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
                } else {
                    result = null
                    progress = 0f
                    stopRequested = false
                    runStartMs = System.currentTimeMillis()
                    nowMs = runStartMs
                    running = true
                    scope.launch {
                        val r = withContext(Dispatchers.Default) {
                            runGcp(
                                selectedBits,
                                onProgress = { p ->
                                    // progress is a MutableState; safe to set from any thread
                                    progress = p
                                },
                                isStopped = { stopRequested }
                            )
                        }
                        result = r
                        running = false
                    }
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
                Text(
                    "Ones: ${r.ones} of ${r.totalBits} bits\n" +
                        "Deviation: ${"%+d".format(r.ones - r.totalBits / 2)}   " +
                        "z = ${"%.3f".format(r.zScore)}  " +
                        "(|z| ${if (abs(r.zScore) > 1.96) ">" else "≤"} 1.96)\n" +
                        "Elapsed: ${"%.2f".format(r.elapsedMs / 1000.0)} s",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
