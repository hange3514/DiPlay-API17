-keep public class local.headunit.diplay.MainActivity { *; }
-keep public class local.headunit.diplay.WiredService { *; }
-keep class local.headunit.diplay.NativeUsb { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keepnames class * extends java.lang.Throwable
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.naming.**
-dontwarn java.lang.invoke.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn javax.security.auth.**
-dontwarn java.awt.**
-dontwarn org.slf4j.**

-keep class local.headunit.diplay.AutoUsbService { *; }
-keep class local.headunit.diplay.AutoBootReceiver { *; }
-keep class local.headunit.diplay.MediaButtonWheelReceiver { *; }

# Stable production media boundary used by the separate emulator instrumentation APK.
-keep class local.headunit.diplay.LegacyMedia { public *; }
-keep class local.headunit.diplay.MediaWorkers { public *; }
-keep class com.shilapi.xcertplay.airplay.AudioStreamId { public *; }
-keep class com.shilapi.xcertplay.airplay.AudioFormat { public *; }
-keep class com.shilapi.xcertplay.airplay.AudioCodecKind { *; }
-keep class kotlin.Unit { *; }
-keep interface kotlin.jvm.functions.Function1 { *; }

-keep class local.headunit.diplay.PlaybackFocus { public *; }
-keep interface local.headunit.diplay.PlaybackFocus$Volume { *; }

-keep class local.headunit.diplay.BydMultimediaCallRoute { public *; }

-keep class local.headunit.diplay.BoundedPacketIo { public *; }
-keep class local.headunit.diplay.PlatformSupport { public *; }

-keep class local.headunit.diplay.DiagnosticLog { *; }

-keep interface local.headunit.diplay.MainActivity$Work { *; }

-keep class local.headunit.diplay.ReportIo { *; }
-keep class local.headunit.diplay.EnvironmentCheck { *; }
-keep class local.headunit.diplay.NetworkDeadline { *; }
-keep class local.headunit.diplay.UpdateFiles { *; }
-keep class local.headunit.diplay.OnlineUpdate { *; }
-keep interface kotlin.jvm.functions.Function0 { *; }
-keep class local.headunit.diplay.VerifiedMfiCache { public *; }
-keep class com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient { public *; }

-keep class local.headunit.diplay.PlaybackOptions { public *; }
-keep class local.headunit.diplay.VpnAutoConfirmService { *; }
-keep class local.headunit.diplay.AccessibilityEntry { *; }
-keep class local.headunit.diplay.NativeCrypto { *; }

-keep class com.shilapi.xcertplay.airplay.AirPlayCrypto { public *; }

-keep class local.headunit.diplay.AudioRouting { public *; }
-keep class local.headunit.diplay.OemAudioObserver { public *; }

-keep class local.headunit.diplay.WheelProbe { *; }
-keepclassmembers class local.headunit.diplay.PlaybackFocus { private android.media.AudioManager$OnAudioFocusChangeListener sessionListener; }

-keep class local.headunit.diplay.WheelControl { *; }
-keep interface local.headunit.diplay.WheelControl$Sink { *; }

-keep class local.headunit.diplay.RecorderNativeStart { public *; }

-keep class local.headunit.diplay.AlsaProbe { *; }

-keep class local.headunit.diplay.LegacyMedia$Microphone { public *; }
-keep class com.shilapi.xcertplay.airplay.MicrophoneConfig { public *; }

-keep class local.headunit.diplay.UsbBatchSequence { *; }
-keep class local.headunit.diplay.UsbBatchReport { *; }
-keep class local.headunit.diplay.UsbBatchCapture { *; }
-keep class local.headunit.diplay.UsbBatchRunner { *; }

-keep class local.headunit.diplay.SystemAudioHarvest { *; }
