package local.headunit.diplay
import android.content.Context

/** This MX9A0 build uses the real-car verified low-load profile. Old preferences are ignored. */
object PlaybackOptions {
 @JvmStatic fun rates()=intArrayOf(15)
 @JvmStatic fun normalize(value:Int)=15
 @JvmStatic fun read(context:Context)=15
}
