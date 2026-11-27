package com.nuvio.tv.core.player.firetvfel

import android.media.MediaCodec
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/** The Fire TV native FEL path cannot continue; the player re-prepares the stream on the AUTO path. */
class FireTvFelException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object FireTvFelFallback {
    /** True when [error] (or a cause) asks for the native FEL path to be abandoned for this stream. */
    fun isFallbackError(error: Throwable?): Boolean {
        var t = error
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is FireTvFelException) return true
            t = t.cause
        }
        return false
    }
}

/**
 * Wraps the video [MediaCodecAdapter] of a Profile 7 stream so every access unit passes through dvfelplay
 * before it reaches the Dolby Vision decoder.
 *
 * Per sample: the native layer rewrites the input buffer in place (FEL: enhancement layer split off to its own
 * decoder, original RPU kept; MEL: nested enhancement-layer NALs removed), the base layer is queued, and only
 * then the timestamp is committed so the enhancement layer is paired with the base layer that actually entered
 * the codec. A failed queue drops the pending pairing. Seeks flush both decoders; release closes the session.
 *
 * Streams that are not Profile 7, are encrypted, or run without the module are handed to [delegate] untouched.
 *
 * The adapter is a [Proxy] over the [MediaCodecAdapter] interface rather than a subclass, so it does not depend
 * on which adapter helper classes a given Media3 version ships. Only `queueInputBuffer`, `flush` and `release`
 * are intercepted; every other call is forwarded as is.
 */
class FireTvFelCodecAdapterFactory(
    private val delegate: MediaCodecAdapter.Factory
) : MediaCodecAdapter.Factory {

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val format = configuration.format
        val native = FireTvFel.isProfile7(format) && format.drmInitData == null && FireTvFel.available()
        if (!native) return delegate.createAdapter(configuration)
        // A Profile 7 sample can only be composed through the Dolby Vision decoder. Any other decoder here means
        // the platform did not offer one; failing now lets the player fall back instead of showing a base layer
        // that carries the enhancement-layer NALs.
        if (configuration.codecInfo.mimeType != MimeTypes.VIDEO_DOLBY_VISION) {
            throw FireTvFelException(
                "Native Profile 7 needs the Dolby Vision decoder, got ${configuration.codecInfo.name} " +
                    "(${configuration.codecInfo.mimeType})"
            )
        }
        val inner = delegate.createAdapter(configuration)
        Log.i(TAG, "Fire TV native P7 decoder: ${configuration.codecInfo.name} ${format.width}x${format.height}")
        val handler = FelAdapterHandler(inner, NativeFelSession(format.width, format.height))
        return Proxy.newProxyInstance(
            MediaCodecAdapter::class.java.classLoader,
            arrayOf(MediaCodecAdapter::class.java),
            handler
        ) as MediaCodecAdapter
    }

    private companion object {
        const val TAG = "FireTvFel"
    }
}

/** One dvfelplay session (JNI handle) for one decoder instance. All calls come from the codec input thread. */
internal class NativeFelSession(width: Int, height: Int) {
    private var handle = FireTvFel.nativeCreate(width, height)
    private var felReported = false

    fun prepare(data: ByteBuffer, offset: Int, size: Int): Int {
        if (handle == 0L) throw FireTvFelException("Fire TV FEL session is closed")
        val outputSize = try {
            FireTvFel.nativePrepare(handle, data, offset, size)
        } catch (failure: IllegalStateException) {
            throw FireTvFelException(failure.message ?: "Fire TV FEL input failed", failure)
        }
        if (!felReported && FireTvFel.nativeIsFel(handle)) {
            felReported = true
            FireTvFel.felSessionActive = true
        }
        return outputSize
    }

    fun commit(pts: Long) {
        if (handle != 0L) FireTvFel.nativeCommit(handle, pts)
    }

    fun flush() {
        if (handle != 0L) FireTvFel.nativeFlush(handle)
    }

    fun close() {
        if (handle != 0L) FireTvFel.nativeClose(handle)
        handle = 0L
        felReported = false
        FireTvFel.felSessionActive = false
    }
}

private class FelAdapterHandler(
    private val inner: MediaCodecAdapter,
    private val session: NativeFelSession
) : InvocationHandler {
    private val lock = Any()

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val a: Array<out Any?> = args ?: NO_ARGS
        if (method.declaringClass == Any::class.java) {
            return when (method.name) {
                "equals" -> proxy === a[0]
                "hashCode" -> System.identityHashCode(proxy)
                else -> "FireTvFelCodecAdapter($inner)"
            }
        }
        try {
            when {
                isQueueInputBuffer(method) -> {
                    queue(a[0] as Int, a[1] as Int, a[2] as Int, a[3] as Long, a[4] as Int)
                    return null
                }
                method.name == "flush" && a.isEmpty() -> {
                    synchronized(lock) {
                        inner.flush()
                        session.flush()
                    }
                    return null
                }
                method.name == "release" && a.isEmpty() -> {
                    synchronized(lock) {
                        try {
                            inner.release()
                        } finally {
                            session.close()
                        }
                    }
                    return null
                }
            }
            return method.invoke(inner, *a)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun queue(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        synchronized(lock) {
            val isSample = size > 0 && (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
            val outputSize = if (isSample) {
                val buffer = inner.getInputBuffer(index)
                    ?: throw FireTvFelException("No codec input buffer for index $index")
                session.prepare(buffer, offset, size)
            } else {
                size
            }
            try {
                inner.queueInputBuffer(index, offset, outputSize, presentationTimeUs, flags)
                // The enhancement layer is paired by the exact timestamp the base layer entered the codec with.
                if (isSample) session.commit(presentationTimeUs)
            } catch (failure: Exception) {
                session.flush()
                throw failure
            }
        }
    }

    /** MediaCodecAdapter.queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags). */
    private fun isQueueInputBuffer(method: Method): Boolean {
        val p = method.parameterTypes
        val int = Int::class.javaPrimitiveType
        return p.size == 5 && p[0] == int && p[1] == int && p[2] == int &&
            p[3] == Long::class.javaPrimitiveType && p[4] == int
    }

    private companion object {
        val NO_ARGS: Array<Any?> = arrayOfNulls(0)
    }
}
