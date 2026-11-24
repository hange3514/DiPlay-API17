package local.headunit.diplay

import android.content.Context
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient

object VerifiedMfiCache {
    private var prepared: PreparedValue<LocalMfiAuthenticationClient>? = null

    @Synchronized private fun value(context: Context): PreparedValue<LocalMfiAuthenticationClient> {
        prepared?.let { return it }
        val app = context.applicationContext
        return PreparedValue {
            LocalMfiAuthenticationClient.load(PlatformSupport.prepareMfi(app))
        }.also { prepared = it }
    }

    @JvmStatic fun warm(context: Context) {
        runCatching { value(context).warm() }
    }

    @JvmStatic @Synchronized fun ready(): Boolean = prepared?.completedValue() != null

    fun get(
        context: Context,
        onSignature: (Int) -> Unit,
        onRetryFailure: (Throwable) -> Unit = {},
    ): LocalMfiAuthenticationClient = value(context).getWithRetry(onRetryFailure).withSignatureObserver(onSignature)
}
