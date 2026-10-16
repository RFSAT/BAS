package com.rfsat.bas.capture

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.rfsat.bas.log.Logger
import java.io.File

/**
 * Random access to the frames of a video file, at full resolution.
 *
 * WHY NOT FastFrameDecoder. That one streams EVERY frame of a window, scaled
 * down to 640 px of luminance, which is right for following a vapour trail
 * through hundreds of frames and wrong for scoring: a string of ten shots
 * needs about twenty frames, and needs each of them at the camera's own
 * resolution, because a 4.5 mm hole is only about ten pixels across even at
 * 1080p with the card filling the frame. Seeking to a handful of instants is
 * exactly what MediaMetadataRetriever is for.
 *
 * ROTATION. MediaMetadataRetriever returns frames already turned upright
 * according to the file's rotation tag, unlike the raw MediaCodec path, which
 * has to apply it by hand. Recorded here because a sideways frame would still
 * register — from the rings — and nothing would look wrong.
 *
 * Opened on a FILE path, never a content:// Uri: the ballistics side records
 * that content Uris can crash MediaMetadataRetriever in native code on some
 * devices, which is not something a try/catch can recover from.
 */
class VideoFrames private constructor(
    private val retriever: MediaMetadataRetriever,
    /** Clip length in seconds, or 0 when the file does not say. */
    val durationS: Double,
    val width: Int,
    val height: Int
) : AutoCloseable {

    /**
     * The frame nearest [tS], or null outside the clip.
     *
     * OPTION_CLOSEST, not the default sync-frame option: a keyframe can be a
     * second or more away, which would put the "before" frame for one shot
     * after the previous one had already been fired.
     */
    fun frameAt(tS: Double): Bitmap? {
        if (tS < 0.0) return null
        if (durationS > 0.0 && tS > durationS) return null
        val us = (tS * 1_000_000).toLong()
        return runCatching {
            retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
        }.onFailure {
            Logger.w(TAG, "no frame at %.3f s: %s".format(tS, it.message))
        }.getOrNull()
    }

    override fun close() {
        runCatching { retriever.release() }
    }

    companion object {
        private const val TAG = "VideoFrames"

        /** Opens [file], or returns null when it is not a readable video. */
        fun open(file: File): VideoFrames? {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(file.absolutePath)
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.div(1000.0) ?: 0.0
                var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                if (w <= 0 || h <= 0) {
                    Logger.w(TAG, "${file.name} has no video track")
                    r.release(); null
                } else {
                    Logger.i(TAG, "%s: %dx%d, %.1f s, rotation %d".format(file.name, w, h, dur, rot))
                    VideoFrames(r, dur, w, h)
                }
            } catch (t: Throwable) {
                Logger.w(TAG, "could not open ${file.name}: ${t.message}")
                runCatching { r.release() }
                null
            }
        }

        /**
         * Copies a picked video into the app's cache so it can be opened by
         * path. Returns null on failure, never a partial file.
         */
        fun copyToCache(context: Context, uri: Uri, name: String = "score_input.mp4"): File? {
            if (uri.scheme == "file") uri.path?.let { p -> File(p).takeIf { it.canRead() }?.let { return it } }
            return try {
                val out = File(context.cacheDir, name)
                context.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) return null
                    out.outputStream().use { input.copyTo(it) }
                }
                if (out.length() == 0L) { out.delete(); null } else out
            } catch (t: Throwable) {
                Logger.w(TAG, "could not copy the video: ${t.message}")
                null
            }
        }

        private val VIDEO = Regex("""\.(mp4|mov|avi|mkv|m4v|3gp)$""", RegexOption.IGNORE_CASE)

        /** True for a path that names a video rather than a still. */
        fun looksLikeVideo(path: String): Boolean = VIDEO.containsMatchIn(path)
    }
}
