package com.nuvio.tv.core.player.firetvfel

import android.os.Build
import android.util.Log
import com.nuvio.tv.BuildConfig
import java.nio.ByteBuffer

/**
 * JNI facade for libfiretvfel.so (app/src/main/cpp/firetvfel): native Dolby Vision Profile 7 FEL and MEL on
 * the Fire TV Cube 3 (Gazelle, `AFTGAZL`).
 *
 * The Cube's Dolby Vision decoder has no enhancement-layer input of its own. With the Fire TV AV Magisk module
 * installed (its shared /dev/dvfel interface), dvfelplay splits each access unit: the base layer and the original
 * Profile 7 RPU go to the platform Dolby Vision MediaCodec, the enhancement layer goes to a second hardware
 * decoder, and the module composes both. Nothing is converted to Profile 8.1. See [FireTvFelCodecAdapterFactory]
 * for how it hooks into ExoPlayer.
 *
 * Only usable on the Cube with the module active; everywhere else [available] is false and nothing changes.
 */
object FireTvFel {
    private const val TAG = "FireTvFel"
    const val CUBE_MODEL = "AFTGAZL"

    private val loaded: Boolean =
        BuildConfig.FIRETV_FEL_NATIVE_ENABLED &&
            Build.MODEL == CUBE_MODEL &&
            runCatching { System.loadLibrary("firetvfel") }
                .onFailure { Log.w(TAG, "libfiretvfel not loaded: ${it.message}") }
                .isSuccess

    /**
     * True while a FEL session (not MEL) is open and composing the enhancement layer. Read by the stats overlay so
     * "RPU + BL + EL" is only claimed when the hardware path is actually running.
     */
    @Volatile
    var felSessionActive: Boolean = false
        internal set

    /** The library is loaded and the module's /dev/dvfel interface is ready (checked on every call). */
    fun available(): Boolean = loaded && runCatching { nativeAvailable() }.getOrDefault(false)

    external fun nativeAvailable(): Boolean

    external fun nativeCreate(width: Int, height: Int): Long

    /** Filters one access unit in place; returns the new size. Throws [IllegalStateException] on a bad stream. */
    external fun nativePrepare(handle: Long, data: ByteBuffer, offset: Int, size: Int): Int

    external fun nativeIsFel(handle: Long): Boolean

    external fun nativeCommit(handle: Long, pts: Long)

    external fun nativeFlush(handle: Long)

    external fun nativeClose(handle: Long)
}
