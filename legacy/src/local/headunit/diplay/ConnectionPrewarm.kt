package local.headunit.diplay

import android.content.Context
import java.io.File

/** Prepare host identity and the dormant lwIP core while the USB watcher idles. */
object ConnectionPrewarm {
    private var identityPending: PreparedValue<IdentityStore>? = null
    private var nativePending: PreparedValue<Boolean>? = null
    @Volatile private var nativePrewarmStatus = "not_started"

    @Synchronized private fun identityTask(context: Context): PreparedValue<IdentityStore> {
        identityPending?.let { return it }
        val app = context.applicationContext
        return PreparedValue {
            val identity = IdentityStore(app)
            identity.warm()
            identity
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

    @JvmStatic @Synchronized fun identityReady(): Boolean = identityPending?.completedValue() != null

    @JvmStatic @Synchronized fun nativeReady(): Boolean = nativePending?.completedValue() == true

    @JvmStatic fun nativePrewarmStatus(): String = nativePrewarmStatus

    @JvmStatic fun identityStore(context: Context): IdentityStore = identityTask(context).get()
}
