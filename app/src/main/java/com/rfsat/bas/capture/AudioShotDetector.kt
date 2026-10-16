package com.rfsat.bas.capture

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.rfsat.bas.log.Logger
import java.nio.ByteOrder

/**
 * Audio shot-break detection (v19.7, accuracy).
 *
 * Every analysis previously trusted the manual shot-break field (default
 * 0.5 s). An error there shifts the settle window and, in tracer mode,
 * directly biases the lag rule (deflection = W x (t - x/v0) — tens of
 * milliseconds matter). The muzzle report is an unmistakable transient in
 * the clip's own audio track, so t0 can be pinned to a few milliseconds —
 * for imported clips just as well as recorded ones.
 *
 * Method: decode the audio track to PCM, track peak amplitude per 5 ms
 * window, then take the FIRST window reaching >= 60% of the global peak
 * (first shot of a string, not the loudest). Two guards keep it honest:
 * no audio track -> null; peak under 4x the median window level (no
 * distinct transient — wind noise, music, silence) -> null. Callers fall
 * back to the manual field on null.
 *
 * 1.54.0 — THE DECODE IS NOW SEPARATE FROM THE CHOOSING, because scoring a
 * STRING from a video needs every report, not the first one. The decode
 * already built a peak per 5 ms window across the whole track and then threw
 * all but one index away; now it hands the array over and two selectors read
 * it:
 *
 *   [detectShotTimeS]  the original: the first window at 60% of the global
 *                      peak. Byte-identical behaviour for the ballistics
 *                      caller, which wants t0 and nothing else.
 *   [detectShotTimesS] every report in the clip, for the scoring side.
 *
 * Both selectors are PURE functions of the window arrays, which is the point:
 * all the judgement lives in code that can be unit-tested without a device,
 * and the Android-coupled part is reduced to decoding.
 */
object AudioShotDetector {

    private const val TAG = "AudioShotDetector"
    private const val WINDOW_S = 0.005
    private const val ONSET_FRACTION = 0.60
    private const val MIN_PEAK_OVER_MEDIAN = 4.0

    /** Multiple of the ambient level that counts as a report. INHERITED from
     *  detect/AudioShotDetector, where it has been doing this job on live
     *  audio across these disciplines rather than being chosen here. */
    private const val TRIGGER_RATIO = 8.0

    /** Minimum gap between reports. Also inherited: shorter than the fastest
     *  cadence the rules allow (ISSF Rapid Fire is 4 s for 5 shots, so 800 ms
     *  apart) and long enough to reject the previous shot's echo. */
    private const val REFRACTORY_S = 0.250

    /**
     * One-pole coefficient for the ambient-level estimate.
     *
     * DERIVED, not picked. The live detector uses 0.02 on blocks of 1024
     * samples at 44100 Hz — 23.2 ms a block, so a time constant of
     * 23.2 ms / 0.02 ≈ 1.16 s. These windows are 5 ms, so holding that same
     * 1.16 s constant means 0.005 / 1.16 ≈ 0.0043. Carrying 0.02 across
     * unchanged would have made the estimate adapt about five times faster
     * than the version that works, which is the direction that loses shots.
     */
    private const val FLOOR_ALPHA = 0.0043
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val MAX_DRAIN_TRIES = 500

    /**
     * What one pass of the audio track yields: the loudest and the average
     * absolute sample in each 5 ms window, and the time the first window
     * starts at.
     *
     * The MEANS are kept as well as the peaks because the ambient-level
     * estimate in [detectShotTimesS] is built from them. A floor fed on
     * peaks sits far above the true ambient level and the trigger ratio
     * then means something different from what it means in the live
     * detector this is ported from.
     */
    internal class Windows(
        val peaks: DoubleArray,
        val means: DoubleArray,
        val baseTimeS: Double
    )

    /** @return time of the shot report in seconds from clip start, or null
     *  if the clip has no audio or no distinct transient. */
    fun detectShotTimeS(videoPath: String): Double? {
        val w = decodeWindows(videoPath) ?: run {
            Logger.i(TAG, "No usable audio — using manual shot-break"); return null
        }
        if (!hasDistinctTransient(w.peaks)) {
            // Kept word for word from before the split: the ballistics Log
            // has always said WHY it fell back, and that is worth more than
            // the line it costs.
            Logger.i(TAG, "No distinct audio transient (peak/median=%.1f) — using manual shot-break"
                .format(peakOverMedian(w.peaks)))
            return null
        }
        val idx = firstOnset(w.peaks) ?: return null
        val tShot = w.baseTimeS.coerceAtLeast(0.0) + idx * WINDOW_S
        Logger.i(TAG, "Shot report detected at %.3f s (peak/median=%.1f)"
            .format(tShot, peakOverMedian(w.peaks)))
        return tShot
    }

    /**
     * Every muzzle report in the clip, in seconds from clip start.
     *
     * WHY A DIFFERENT RULE FROM [detectShotTimeS]. "A fraction of the global
     * peak" finds the first shot and is wrong for the rest of them: shots in
     * one string differ in loudness by more than that fraction — muzzle
     * angle, how much of the report the microphone was facing, a neighbouring
     * firing point — so a bar set by the loudest shot quietly drops the
     * others. This instead tracks the ambient level and fires on a transient
     * that is a large multiple of it, which is the algorithm the LIVE
     * microphone detector (detect/AudioShotDetector) already uses in the
     * field for exactly this job, on exactly these disciplines. Its two
     * constants are inherited rather than invented, and the derivation of the
     * third is spelled out at [FLOOR_ALPHA].
     *
     * @return report times, ascending. Empty when the clip has no audio or
     *   no distinct transient — the caller must then ask the shooter rather
     *   than invent a cadence.
     */
    fun detectShotTimesS(videoPath: String): List<Double> {
        val w = decodeWindows(videoPath) ?: return emptyList()
        if (!hasDistinctTransient(w.peaks)) {
            Logger.i(TAG, "No distinct audio transient (peak/median=%.1f) — no reports timed"
                .format(peakOverMedian(w.peaks)))
            return emptyList()
        }
        val idx = onsets(w.peaks, w.means)
        val base = w.baseTimeS.coerceAtLeast(0.0)
        val times = idx.map { base + it * WINDOW_S }
        Logger.i(TAG, "%d report(s) timed: %s".format(
            times.size, times.joinToString(", ") { "%.3f".format(it) }))
        return times
    }

    // ---- the pure selectors -------------------------------------------

    private fun peakOverMedian(peaks: DoubleArray): Double {
        if (peaks.isEmpty()) return 0.0
        val median = peaks.sorted()[peaks.size / 2]
        return peaks.max() / (median + 1.0)
    }

    /** The guard both selectors share: a track with no distinct transient at
     *  all is wind noise, music or silence, and nothing in it is a shot. */
    internal fun hasDistinctTransient(peaks: DoubleArray): Boolean =
        peaks.size >= 10 && peakOverMedian(peaks) >= MIN_PEAK_OVER_MEDIAN

    /** The original rule, unchanged: the first window at [ONSET_FRACTION] of
     *  the global peak. */
    internal fun firstOnset(peaks: DoubleArray): Int? {
        val threshold = peaks.max() * ONSET_FRACTION
        return peaks.indexOfFirst { it >= threshold }.takeIf { it >= 0 }
    }

    /**
     * Every report, by adaptive floor and refractory gap.
     *
     * The floor is fed ONLY on quiet windows — a report that entered its own
     * average is what would make the detector deaf to the rest of the string,
     * and that reasoning is carried over verbatim from the live detector.
     *
     * One thing this does better than the live version, because it can: the
     * floor starts at the track's own median level instead of a fixed high
     * value. Live, the estimate has to settle before it can be trusted and a
     * shot in the first second is lost; offline the whole track is in hand
     * before any decision is made, so there is no settling period and the
     * first shot is as findable as the last.
     */
    internal fun onsets(peaks: DoubleArray, means: DoubleArray): List<Int> {
        if (peaks.size < 10) return emptyList()
        var floor = means.sorted()[means.size / 2].coerceAtLeast(1.0)
        val out = ArrayList<Int>()
        val refractory = (REFRACTORY_S / WINDOW_S).toInt()
        // One refractory gap before the start, so the first window is already
        // eligible. NOT Int.MIN_VALUE: i - Int.MIN_VALUE overflows to a
        // negative number, the gap test then fails for every window, and the
        // detector silently reports no shots at all. It did.
        var last = -refractory - 1
        for (i in peaks.indices) {
            val loud = peaks[i] > floor * TRIGGER_RATIO
            if (loud) {
                if (i - last > refractory) { out += i; last = i }
            } else {
                floor = (1.0 - FLOOR_ALPHA) * floor + FLOOR_ALPHA * maxOf(means[i], 1.0)
            }
        }
        return out
    }

    /** One pass over the audio track. Android-coupled, and deliberately the
     *  only part that is. */
    private fun decodeWindows(videoPath: String): Windows? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(videoPath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; format = f; break
                }
            }
            if (track < 0 || format == null) {
                Logger.i(TAG, "No audio track")
                return null
            }
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Peak and mean absolute sample per window, indexed by window
            // number. The mean is accumulated as a running sum and count
            // because a window straddles decoder output buffers.
            val windowPeaks = ArrayList<Double>(4096)
            val windowSums = ArrayList<Double>(4096)
            val windowCounts = ArrayList<Int>(4096)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var drainTries = 0
            var baseTimeS = -1.0

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        drainTries = 0
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (info.size > 0) {
                            if (baseTimeS < 0) baseTimeS = info.presentationTimeUs / 1_000_000.0
                            // Output format may carry updated rate/channels.
                            val of = codec.outputFormat
                            if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                                sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                                channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val samplesPerWindow = (sampleRate * WINDOW_S).toInt().coerceAtLeast(1)

                            val bb = codec.getOutputBuffer(outIdx)!!
                            bb.order(ByteOrder.LITTLE_ENDIAN)
                            val sb = bb.asShortBuffer()
                            val tS = info.presentationTimeUs / 1_000_000.0
                            var frame = 0
                            val frames = sb.remaining() / channels
                            while (frame < frames) {
                                // mono mix by first channel (peak detection
                                // doesn't need a proper downmix)
                                val v = sb.get(frame * channels).toInt()
                                val a = if (v < 0) -v.toDouble() else v.toDouble()
                                val wIdx = (((tS - baseTimeS) + frame.toDouble() / sampleRate) / WINDOW_S).toInt()
                                while (windowPeaks.size <= wIdx) {
                                    windowPeaks.add(0.0); windowSums.add(0.0); windowCounts.add(0)
                                }
                                if (a > windowPeaks[wIdx]) windowPeaks[wIdx] = a
                                windowSums[wIdx] = windowSums[wIdx] + a
                                windowCounts[wIdx] = windowCounts[wIdx] + 1
                                frame++
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (++drainTries > MAX_DRAIN_TRIES) {
                            Logger.w(TAG, "Audio decoder stalled"); outputDone = true
                        }
                    }
                }
            }

            if (windowPeaks.size < 10) return null
            val means = DoubleArray(windowPeaks.size) { i ->
                if (windowCounts[i] > 0) windowSums[i] / windowCounts[i] else 0.0
            }
            return Windows(windowPeaks.toDoubleArray(), means, baseTimeS)
        } catch (t: Throwable) {
            Logger.w(TAG, "Audio decode failed: ${t.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
