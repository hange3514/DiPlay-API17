package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
class ConnectionOptionsTest {
 @Test fun selectedRateIsActuallyAdvertisedToPhone(){
  for(fps in PlaybackOptions.rates()){
   val config=com.shilapi.xcertplay.airplay.AirPlayConfig("test","02:00:00:00:00:01","02:00:00:00:00:01","320.20",com.shilapi.xcertplay.airplay.AirPlayDisplayConfig(ProjectionConfig.WIDTH,ProjectionConfig.HEIGHT,fps=fps))
   val info=com.shilapi.xcertplay.airplay.AirPlayInfoPlist.build(config)
   val display=(info["displays"] as List<*>).first() as Map<*,*>
   assertEquals(fps,display["maxFPS"])
   assertEquals(1024,display["widthPixels"])
   assertEquals(600,display["heightPixels"])
   val area=(display["viewAreas"] as List<*>).single() as Map<*,*>
   assertEquals(1024,area["widthPixels"])
   assertEquals(600,area["heightPixels"])
  }
 }

 @Test fun supportedFrameRatesAndInvalidFallback(){assertArrayEquals(intArrayOf(15,20,30,40,50,60),PlaybackOptions.rates());assertArrayEquals(intArrayOf(15,20,30),PlaybackOptions.calibrationRates(false));assertArrayEquals(intArrayOf(30,40,50,60),PlaybackOptions.calibrationRates(true));assertEquals(60,PlaybackOptions.DEFAULT_FPS);for(n in PlaybackOptions.rates())assertEquals(n,PlaybackOptions.normalize(n));for(n in listOf(0,-1,24,120))assertEquals(60,PlaybackOptions.normalize(n))}
 @Test fun callersCannotChangeAllowedRates(){val a=PlaybackOptions.rates();a[0]=100;assertEquals(60,PlaybackOptions.normalize(100))}
 @Test fun onlyActualVpnActivityMatches(){assertTrue(VpnPromptPolicy.window("com.android.vpndialogs","com.android.vpndialogs.ConfirmDialog"));assertFalse(VpnPromptPolicy.window("android","com.android.vpndialogs.ConfirmDialog"));assertFalse(VpnPromptPolicy.window("com.android.vpndialogs","android.app.Dialog"));assertFalse(VpnPromptPolicy.window(null,null))}
 @Test fun noConsentForOtherAppsOrWithoutPendingRequest(){val label="DiPlay有线移植";assertTrue(VpnPromptPolicy.target("允许 $label 创建VPN?",label,true));assertFalse(VpnPromptPolicy.target("允许其他VPN应用",label,true));assertFalse(VpnPromptPolicy.target(label,label,false));assertFalse(VpnPromptPolicy.target("anything","",true))}
 @Test fun cancelAndUnrecognizedButtonsNeverConfirm(){for(s in listOf("取消","拒绝","Cancel","Don't allow",null))assertFalse(VpnPromptPolicy.confirm(s));assertTrue(VpnPromptPolicy.confirm("确定"));assertTrue(VpnPromptPolicy.confirm("OK"))}
}
