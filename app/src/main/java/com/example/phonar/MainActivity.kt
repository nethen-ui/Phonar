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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---------- Tunable settings ----------
private const val SR = 48000            // sample rate (Hz)
private const val F_START = 1000.0
private const val F_END = 4000.0     // chirp end frequency (Hz)
private const val CHIRP_MS = 10         // chirp length (ms)
private const val LISTEN_MS = 350       // recording window per ping (ms)
private const val SPEED_OF_SOUND = 343.0
private const val MIN_GAP = 60          // ignore echoes closer than ~0.2 m (samples after direct sound)
private const val MAX_RANGE_M = 6.0     // farthest echo we look for
private const val SNR_THRESHOLD = 5f    // echo peak must be this many times above noise floor
// --------------------------------------

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
    private fun makeChirp(): FloatArray {
        val n = SR * CHIRP_MS / 1000
        val T = n.toDouble() / SR
        val k = (F_END - F_START) / T
        return FloatArray(n) { i ->
            val t = i.toDouble() / SR
            val phase = 2 * PI * (F_START * t + 0.5 * k * t * t)
            val window = 0.5 - 0.5 * cos(2 * PI * i / (n - 1)) // Hann window = smooth start/end
            (sin(phase) * window).toFloat()
        }
    }

    // ---------- Main loop: ping -> record -> analyse ----------
    @SuppressLint("MissingPermission")
    private fun sonarLoop() {
        val chirp = makeChirp()
        val n = chirp.size

        // Short PCM version of the chirp (+ a little silence after it)
        val pcm = ShortArray(n * 2)
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
        track.play()

        try {
            while (running) {
                // 1) start recording, 2) fire the chirp, 3) read the whole listening window
                rec.startRecording()
                track.write(pcm, 0, pcm.size)
                var got = 0
                while (got < m && running) {
                    val r = rec.read(buf, got, m - got)
                    if (r <= 0) break
                    got += r
                }
                rec.stop()
                if (got < m) break

                val result = analyse(buf, chirp)
                runOnUiThread {
                    if (result == null) {
                        infoText.text = "No clear echo (try closer/louder, or check the chirp band)"
                    } else {
                        recent.addLast(result.first)
                        if (recent.size > 5) recent.removeFirst()
                        val median = recent.sorted()[recent.size / 2]
                        distanceText.text = String.format("%.2f m", median)
                        infoText.text = String.format(
                            "last ping: %.2f m   echo strength: %.1fx noise",
                            result.first, result.second
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
     * Cross-correlates the recording with the chirp (matched filter).
     * The strongest early peak is the direct speaker->mic sound (our time reference),
     * the next strong peak after it is the wall echo.
     * Returns Pair(distance in meters, echo strength relative to noise) or null.
     */
    private fun analyse(raw: ShortArray, chirp: FloatArray): Pair<Double, Float>? {
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
            var s = 0f
            for (i in 0 until n) s += x[k + i] * chirp[i]
            c[k] = abs(s)
        }

        var maxV = 0f
        for (v in c) if (v > maxV) maxV = v
        if (maxV < 0.01f) return null // nothing heard at all

        // direct path: first sample above half of the global max, refined to the local peak
        var d = 0
        while (d < len && c[d] < 0.5f * maxV) d++
        var best = d
        for (k in d until min(d + 40, len)) if (c[k] > c[best]) best = k
        d = best

        val maxGap = (2.0 * MAX_RANGE_M / SPEED_OF_SOUND * SR).toInt()
        val lo = d + MIN_GAP
        val hi = d + maxGap
        val tail = 2000
        if (hi >= len - tail) return null

        var noise = 0f
        for (k in len - tail until len) noise += c[k]
        noise /= tail
        if (noise <= 0f) return null

        var e = lo
        for (k in lo..hi) if (c[k] > c[e]) e = k
        val strength = c[e] / noise
        if (strength < SNR_THRESHOLD) return null

        val seconds = (e - d).toDouble() / SR
        return Pair(seconds * SPEED_OF_SOUND / 2.0, strength)
    }
}
