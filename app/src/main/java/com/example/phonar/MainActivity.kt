package com.example.phonar

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ---------- Tunable settings ----------
private const val SR = 48000            // sample rate (Hz)
private const val F_START = 2000.0      // chirp start frequency (Hz)  (audible debug band; later 16000.0)
private const val F_END = 8000.0        // chirp end frequency (Hz)  (later 20000.0)
private const val CHIRP_MS = 10         // chirp length (ms)
private const val LISTEN_MS = 350       // recording window per ping (ms)
private const val SPEED_OF_SOUND = 343.0
private const val MIN_GAP = 60          // ignore echoes closer than ~0.2 m (samples after direct sound)
private const val MAX_RANGE_M = 6.0     // farthest echo we look for
private const val SNR_THRESHOLD = 5f    // echo peak must be this many times above noise floor
private const val REL_THRESHOLD = 0.3f  // pick the NEAREST peak that is >= this fraction of the biggest peak
// --------------------------------------

class Echo(val meters: Double?, val strength: Float, val debug: String, val level: Float)

class MainActivity : ComponentActivity() {

    private lateinit var distanceText: TextView
    private lateinit var infoText: TextView
    private lateinit var button: Button

    @Volatile private var running = false
    private var worker: Thread? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPinging() else infoText.text = "Microphone permission is required."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Phonar test"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        distanceText = TextView(this).apply {
            text = "-- m"
            textSize = 64f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        infoText = TextView(this).apply {
            text = "Point the bottom of the phone at a flat wall, then press start."
            textSize = 14f
            gravity = Gravity.CENTER
        }
        button = Button(this).apply {
            text = "Start emitting"
            setOnClickListener { toggle() }
        }
        root.addView(title)
        root.addView(distanceText)
        root.addView(infoText)
        root.addView(button)
        setContentView(root)
    }

    private fun toggle() {
        if (running) {
            stopPinging()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startPinging()
        } else {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startPinging() {
        if (running) return
        running = true
        button.text = "Stop"
        worker = Thread { sonarLoop() }.also { it.start() }
    }

    private fun stopPinging() {
        running = false
        button.text = "Start emitting"
        worker = null
    }

    override fun onPause() {
        super.onPause()
        if (running) stopPinging()
    }

    // ---------- Chirp generation ----------
    // quadrature = true gives the 90-degree shifted copy, used to build a smooth envelope
    private fun makeChirp(quadrature: Boolean): FloatArray {
        val n = SR * CHIRP_MS / 1000
        val T = n.toDouble() / SR
        val k = (F_END - F_START) / T
        return FloatArray(n) { i ->
            val t = i.toDouble() / SR
            val phase = 2 * PI * (F_START * t + 0.5 * k * t * t)
            val window = 0.5 - 0.5 * cos(2 * PI * i / (n - 1)) // Hann window
            val v = if (quadrature) cos(phase) else sin(phase)
            (v * window).toFloat()
        }
    }

    // ---------- Main loop: ping -> record -> analyse ----------
    @SuppressLint("MissingPermission")
    private fun sonarLoop() {
        val chirp = makeChirp(false)
        val quad = makeChirp(true)
        val n = chirp.size
        var en = 0.0
        for (v in chirp) en += (v * v).toDouble()
        val energy = en.toFloat()

        // Only the chirp itself is played (no padding), one clean ping per cycle
        val pcm = ShortArray(n)
        for (i in 0 until n) pcm[i] = (chirp[i] * 0.95f * Short.MAX_VALUE).toInt().toShort()

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val unprocessed =
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source =
            if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC

        val m = SR * LISTEN_MS / 1000
        val recMin = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            source, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(recMin, m * 2) * 2
        )

        val trackMin = AudioTrack.getMinBufferSize(
            SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(trackMin, pcm.size * 2) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runOnUiThread { infoText.text = "Could not open the microphone."; stopPinging() }
            track.release(); rec.release()
            return
        }

        val recent = ArrayDeque<Double>()
        val buf = ShortArray(m)

        try {
            while (running) {
                // Fresh start every ping: play -> record -> stop -> flush (so nothing is ever replayed)
                track.play()
                rec.startRecording()
                track.write(pcm, 0, pcm.size)
                var got = 0
                while (got < m && running) {
                    val r = rec.read(buf, got, m - got)
                    if (r <= 0) break
                    got += r
                }
                rec.stop()
                track.pause()
                track.flush()
                if (got < m) break

                val result = analyse(buf, chirp, quad, energy)
                runOnUiThread {
                    if (result == null) {
                        infoText.text = "Mic hears almost nothing of the chirp. Raise media volume."
                    } else if (result.meters == null) {
                        infoText.text = String.format("No clear echo\nown chirp level: %.3f\n%s", result.level, result.debug)
                    } else {
                        recent.addLast(result.meters)
                        if (recent.size > 5) recent.removeFirst()
                        val median = recent.sorted()[recent.size / 2]
                        distanceText.text = String.format("%.2f m", median)
                        infoText.text = String.format(
                            "last ping: %.2f m  (%.1fx noise)\nown chirp level: %.3f\n%s",
                            result.meters, result.strength, result.level, result.debug
                        )
                    }
                }
                Thread.sleep(100)
            }
        } finally {
            try { track.stop() } catch (e: Exception) {}
            track.release()
            try { rec.stop() } catch (e: Exception) {}
            rec.release()
        }
    }

    /**
     * Matched filter with an in-phase and a quadrature template, giving a smooth envelope.
     * Only energy that looks like the chirp (same band and sweep) produces a peak.
     * The strongest early peak = direct speaker->mic sound (time reference).
     * The NEAREST strong peak after it = closest reflecting surface.
     */
    private fun analyse(raw: ShortArray, chirp: FloatArray, quad: FloatArray, energy: Float): Echo? {
        val m = raw.size
        val n = chirp.size
        val x = FloatArray(m)
        var mean = 0f
        for (i in 0 until m) { x[i] = raw[i] / 32768f; mean += x[i] }
        mean /= m
        for (i in 0 until m) x[i] -= mean

        val len = m - n
        val c = FloatArray(len)
        for (k in 0 until len) {
            var si = 0f
            var sq = 0f
            for (i in 0 until n) {
                val v = x[k + i]
                si += v * chirp[i]
                sq += v * quad[i]
            }
            c[k] = sqrt(si * si + sq * sq)
        }

        var maxV = 0f
        for (v in c) if (v > maxV) maxV = v
        if (maxV / energy < 0.002f) return null

        // direct path: first sample above half of the global max, refined to the local peak
        var d = 0
        while (d < len && c[d] < 0.5f * maxV) d++
        var best = d
        for (k in d until min(d + 40, len)) if (c[k] > c[best]) best = k
        d = best
        val level = c[d] / energy   // roughly: loudness of our own chirp at the mic (1.0 = full scale)

        val maxGap = (2.0 * MAX_RANGE_M / SPEED_OF_SOUND * SR).toInt()
        val lo = d + MIN_GAP
        val hi = d + maxGap
        val tail = 2000
        if (hi >= len - tail) return Echo(null, 0f, "direct@$d too late in window", level)

        var noise = 0f
        for (k in len - tail until len) noise += c[k]
        noise /= tail
        if (noise <= 0f) return null
        val thr = SNR_THRESHOLD * noise

        // local maxima (+-20 samples) above the threshold
        val cands = ArrayList<Pair<Int, Float>>()
        for (k in lo..hi) {
            if (c[k] <= thr) continue
            var isPeak = true
            for (j in max(0, k - 20)..min(len - 1, k + 20)) {
                if (c[j] > c[k]) { isPeak = false; break }
            }
            if (isPeak) cands.add(Pair(k, c[k]))
        }

        fun meters(k: Int) = (k - d).toDouble() / SR * SPEED_OF_SOUND / 2.0
        val dbg = "peaks: " + cands.sortedByDescending { it.second }.take(4)
            .joinToString("   ") { String.format("%.2fm(%.0fx)", meters(it.first), it.second / noise) }

        if (cands.isEmpty()) return Echo(null, 0f, "no peaks above ${SNR_THRESHOLD}x noise", level)

        val biggest = cands.maxOf { it.second }
        val chosen = cands.first { it.second >= REL_THRESHOLD * biggest } // list is in position order
        return Echo(meters(chosen.first), chosen.second / noise, dbg, level)
    }
}
