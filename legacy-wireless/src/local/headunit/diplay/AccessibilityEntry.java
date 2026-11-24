package local.headunit.diplay;
import android.content.Intent;
import android.provider.Settings;

/** Launch system-owned UI only. Does not enable any accessibility service. */
final class AccessibilityEntry {
 static Intent intent(int route){
  if(route==3)return intent(0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra(":android:show_fragment","com.android.settings.AccessibilitySettings").putExtra(":android:no_headers",true);
  if(route==0)return new Intent().setClassName("com.android.settings","com.android.settings.Settings$AccessibilitySettingsActivity");
  if(route==1)return new Intent().setClassName("com.android.settings","com.android.settings.Settings")
   .putExtra(":android:show_fragment","com.android.settings.AccessibilitySettings")
   .putExtra(":android:no_headers",true);
  return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
 }
}
