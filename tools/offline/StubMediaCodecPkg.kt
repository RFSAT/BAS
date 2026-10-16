package android.media

/** Only what the RTSP client touches. Real signatures, so a wrong call fails
 *  here rather than in CI. */
class MediaCodec private constructor() {
    class BufferInfo {
        var size: Int = 0
        var presentationTimeUs: Long = 0
        var flags: Int = 0
        fun set(offset: Int, size: Int, ptsUs: Long, flags: Int) {}
    }
    fun configure(format: MediaFormat, surface: android.view.Surface?, crypto: Any?, flags: Int) {}
    fun start() {}
    fun stop() {}
    fun release() {}
    fun dequeueInputBuffer(timeoutUs: Long): Int = -1
    fun getInputBuffer(index: Int): java.nio.ByteBuffer? = null
    fun queueInputBuffer(index: Int, offset: Int, size: Int, ptsUs: Long, flags: Int) {}
    fun dequeueOutputBuffer(info: BufferInfo, timeoutUs: Long): Int = -1
    fun releaseOutputBuffer(index: Int, render: Boolean) {}
    fun getOutputBuffer(index: Int): java.nio.ByteBuffer? = null
    val outputFormat: MediaFormat get() = MediaFormat()
    companion object {
        const val BUFFER_FLAG_KEY_FRAME = 1
        const val BUFFER_FLAG_END_OF_STREAM = 4
        const val INFO_TRY_AGAIN_LATER = -1
        @JvmStatic fun createDecoderByType(mime: String): MediaCodec = MediaCodec()
    }
}

class MediaFormat {
    fun setByteBuffer(name: String, value: java.nio.ByteBuffer) {}
    fun getString(name: String): String? = null
    fun getInteger(name: String): Int = 0
    fun containsKey(name: String): Boolean = false
    companion object {
        const val MIMETYPE_VIDEO_AVC = "video/avc"
        const val KEY_MIME = "mime"
        const val KEY_SAMPLE_RATE = "sample-rate"
        const val KEY_CHANNEL_COUNT = "channel-count"
        @JvmStatic fun createVideoFormat(mime: String, w: Int, h: Int): MediaFormat = MediaFormat()
    }
}

/** The offline audio decoder in capture/AudioShotDetector. */
class MediaExtractor {
    fun setDataSource(path: String) {}
    val trackCount: Int get() = 0
    fun getTrackFormat(index: Int): MediaFormat = MediaFormat()
    fun selectTrack(index: Int) {}
    fun readSampleData(buffer: java.nio.ByteBuffer, offset: Int): Int = -1
    val sampleTime: Long get() = -1
    fun advance(): Boolean = false
    fun release() {}
}

/** Random-access frames, for capture/VideoFrames. */
class MediaMetadataRetriever {
    fun setDataSource(path: String) {}
    fun extractMetadata(keyCode: Int): String? = null
    fun getFrameAtTime(timeUs: Long, option: Int): android.graphics.Bitmap? = null
    fun release() {}
    companion object {
        const val OPTION_CLOSEST = 3
        const val METADATA_KEY_DURATION = 9
        const val METADATA_KEY_VIDEO_WIDTH = 18
        const val METADATA_KEY_VIDEO_HEIGHT = 19
        const val METADATA_KEY_VIDEO_ROTATION = 24
    }
}
