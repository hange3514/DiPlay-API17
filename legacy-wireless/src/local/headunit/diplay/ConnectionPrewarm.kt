package local.headunit.diplay

import android.content.Context
import java.io.File

/** Prepare connection materials while the transport is being established. */
object ConnectionPrewarm {
    private var identityPending: PreparedValue<IdentityStore>? = null
    private var nativePending: PreparedValue<Boolean>? = null
    private val uplinkOpusWarmup = PreparedValue(launch = { work ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            work.run()
        }, "diplay-prepare-mic-codec").apply { isDaemon = true; start() }
    }) {
        WirelessOpus.Encoder(48_000, 2, true).use { encoder ->
            val frame = ByteArray(1_920)
            repeat(4) { part ->
                for (sample in 0 until 960) {
                    val phase = (part * 960 + sample) % 240
                    val triangle = if (phase < 120) phase - 60 else 180 - phase
                    val value = triangle * (24 + part * 12)
                    frame[sample * 2] = value.toByte()
                    frame[sample * 2 + 1] = (value shr 8).toByte()
                }
                if (encoder.encode(frame) == null) return@PreparedValue false
            }
            true
        }
    }
    @Volatile private var nativePrewarmStatus = "not_started"

    @Synchronized private fun identityTask(context: Context): PreparedValue<IdentityStore> {
        identityPending?.let { return it }
        val app = context.applicationContext
        return PreparedValue {
            IdentityStore(app).also { store ->
                store.identity()
                store.pairings()
            }
        }.also { identityPending = it }
    }

    /** The lwIP core thread is process-wide; start it during idle time, not on the USB path. */
    @Synchronized private fun nativeTask(context: Context): PreparedValue<Boolean> {
        nativePending?.let { return it }
        val app = context.applicationContext
        return PreparedValue {
            var libraryLoaded = false
            nativePrewarmStatus = "running"
            try {
                NativeIpStack.installCrashCapture(File(app.filesDir, "native-crash.txt").absolutePath)
                libraryLoaded = true
                NativeIpStack.setCrashStage("native_prewarm_before")
                NativeIpStack.preload()
                nativePrewarmStatus = "ready"
                true
            } catch (failure: Throwable) {
                if (libraryLoaded) runCatching { NativeIpStack.setCrashStage("native_prewarm_failed") }
                nativePrewarmStatus = "failed_${failure.javaClass.simpleName.take(40)}"
                false // The first real stack creation still reports the native error code.
            }
        }.also { nativePending = it }
    }

    @JvmStatic fun warm(context: Context) {
        runCatching { identityTask(context).warm() }
        runCatching { nativeTask(context).warm() }.onFailure { failure ->
            nativePrewarmStatus = "failed_${failure.javaClass.simpleName.take(40)}"
        }
    }

    @JvmStatic fun warmWireless(context: Context) {
        runCatching { identityTask(context).warm() }
        VerifiedMfiCache.warm(context)
        if (HotspotEvidence.supported()) runCatching { uplinkOpusWarmup.warm() }
    }

    @JvmStatic @Synchronized fun identityReady(): Boolean = identityPending?.completedValue() != null

    @JvmStatic @Synchronized fun nativeReady(): Boolean = nativePending?.completedValue() == true

    @JvmStatic fun nativePrewarmStatus(): String = nativePrewarmStatus

    @JvmStatic fun identityStore(context: Context): IdentityStore = identityTask(context).getWithRetry()
}

internal fun <T> PreparedValue<T>.getWithRetry(onRetryFailure: (Throwable) -> Unit = {}): T {
    return try {
        get()
    } catch (interrupted: InterruptedException) {
        throw interrupted
    } catch (failure: Throwable) {
        runCatching { onRetryFailure(failure) }
        get()
    }
}
