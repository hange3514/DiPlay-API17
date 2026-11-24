package local.headunit.diplay;

import android.content.*;
import android.content.pm.*;
import android.provider.Settings;
import java.io.File;
import org.json.*;

/** Read-only capabilities, never injects input or enables debugging/services. */
final class AutomationEvidence {
 static JSONObject collect(Context c)throws Exception {
  PackageManager pm=c.getPackageManager();JSONObject out=new JSONObject();
  JSONArray permissions=new JSONArray();
  for(String name:new String[]{"INJECT_EVENTS","READ_FRAME_BUFFER","WRITE_SECURE_SETTINGS","DUMP"}) {
   String full="android.permission."+name;JSONObject item=new JSONObject().put("name",full).put("granted",c.checkCallingOrSelfPermission(full)==PackageManager.PERMISSION_GRANTED);
   try{item.put("protectionLevel",pm.getPermissionInfo(full,0).protectionLevel);}catch(PackageManager.NameNotFoundException e){item.put("definition","missing");}
   permissions.put(item);
  }
  out.put("permissions",permissions).put("uid",android.os.Process.myUid()).put("vpnHelperConnected",VpnAutoConfirmService.connected);
  try{String enabled=Settings.Secure.getString(c.getContentResolver(),"enabled_accessibility_services");boolean own=false;if(enabled!=null)for(String value:enabled.split(":")){ComponentName n=ComponentName.unflattenFromString(value);if(n!=null&&c.getPackageName().equals(n.getPackageName())&&VpnAutoConfirmService.class.getName().equals(n.getClassName()))own=true;}out.put("vpnHelperEnabled",own).put("accessibilityEnabled",Settings.Secure.getInt(c.getContentResolver(),"accessibility_enabled",0));}catch(SecurityException e){out.put("accessibilitySettings","denied");}
  JSONArray entries=new JSONArray();for(ResolveInfo r:pm.queryIntentActivities(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),PackageManager.GET_META_DATA)){if(entries.length()>=16)break;ActivityInfo a=r.activityInfo;if(a!=null)entries.put(activity(c,a));}out.put("accessibilityIntentHandlers",entries);
  JSONArray routes=new JSONArray();for(int route=0;route<4;route++){ResolveInfo resolved=pm.resolveActivity(AccessibilityEntry.intent(route),PackageManager.GET_META_DATA);JSONObject item=new JSONObject().put("route",route).put("resolved",resolved!=null&&resolved.activityInfo!=null).put("pageVisible","requires_user_confirmation");if(resolved!=null&&resolved.activityInfo!=null)item.put("target",activity(c,resolved.activityInfo));routes.put(item);}out.put("accessibilityRoutes",routes);
  JSONArray candidates=new JSONArray();try{PackageInfo p=pm.getPackageInfo("com.android.settings",PackageManager.GET_ACTIVITIES|PackageManager.GET_DISABLED_COMPONENTS);if(p.activities!=null)for(ActivityInfo a:p.activities){if(candidates.length()>=32)break;if(a.name.toLowerCase(java.util.Locale.US).matches(".*(accessibility|development|subsettings|factory|engineering).*"))candidates.put(activity(c,a));}}catch(PackageManager.NameNotFoundException e){out.put("settingsPackage","missing");}out.put("settingsActivityCandidates",candidates);
  try{out.put("adbEnabled",Settings.Global.getInt(c.getContentResolver(),"adb_enabled",0));}catch(SecurityException e){out.put("adbEnabled","denied");}
  JSONObject properties=new JSONObject();for(String key:new String[]{"init.svc.adbd","service.adb.tcp.port","persist.adb.tcp.port","ro.adb.secure","ro.debuggable","ro.secure","sys.usb.config","persist.sys.usb.config","ro.build.type","ro.build.tags","ro.build.fingerprint","ro.bootloader","ro.boot.flash.locked","ro.boot.verifiedbootstate","ro.oem_unlock_supported"}){try{Object value=Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,key);properties.put(key,value);}catch(Exception e){properties.put(key,"unavailable");}}out.put("adbProperties",properties);out.put("kernelVersion",UsbPresenceLog.read(new File("/proc/version"))).put("selinuxEnforce",UsbPresenceLog.read(new File("/sys/fs/selinux/enforce")));
  JSONArray recovery=new JSONArray();for(String path:new String[]{"/system/bin/run-as","/system/bin/debuggerd","/system/bin/adb","/system/bin/adbd","/sbin/adbd","/dev/block/by-name","/dev/block/platform","/system/etc/install-recovery.sh"})recovery.put(file(path));out.put("recoveryPrerequisites",recovery).put("rootAssessment","No exploit executed; old Android alone does not establish exploitability; recovery image and bootloader support unverified");
  JSONArray files=new JSONArray();for(String path:new String[]{"/system/bin/input","/system/bin/screencap","/system/bin/su","/system/xbin/su","/dev/graphics/fb0","/dev/uinput"})files.put(file(path));
  File[] nodes=new File("/dev/input").listFiles();if(nodes!=null)for(int n=0;n<Math.min(nodes.length,32);n++)files.put(file(nodes[n].getPath()));out.put("files",files).put("inputNodesStatus",nodes==null?"unavailable_or_denied":"listed");
  return out.put("scope","passive_only_no_click_no_screenshot_no_adb_connection_no_root_attempt").put("adbUsable","not_tested_properties_do_not_prove_connectivity_or_authorization").put("coordinateClick","not_tested_permissions_and_file_access_are_only_prerequisites");
 }
 static JSONObject settingsClasses(Context c)throws Exception {
  ApplicationInfo info=c.getPackageManager().getApplicationInfo("com.android.settings",0);JSONArray names=new JSONArray();int scanned=0;dalvik.system.DexFile dex=null;
  try{dex=new dalvik.system.DexFile(info.sourceDir);java.util.Enumeration<String> entries=dex.entries();while(entries.hasMoreElements()&&scanned<60000){String name=entries.nextElement();scanned++;if(name.toLowerCase(java.util.Locale.US).contains("accessibility")&&names.length()<64)names.put(name);}return new JSONObject().put("classes",names).put("scanned",scanned).put("truncated",entries.hasMoreElements()).put("scope","dex_class_names_only_no_code_execution_or_settings_changes");}
  finally{if(dex!=null)dex.close();}
 }
 static JSONObject activity(Context c,ActivityInfo a)throws JSONException {return new JSONObject().put("package",a.packageName).put("activity",a.name).put("launchMode",a.launchMode).put("taskAffinity",a.taskAffinity==null?"":a.taskAffinity).put("targetActivity",a.targetActivity==null?"":a.targetActivity).put("fragment",a.metaData==null?"":a.metaData.getString("com.android.settings.FRAGMENT_CLASS", "")).put("exported",a.exported).put("enabled",a.enabled&&a.applicationInfo.enabled).put("requiredPermission",a.permission==null?"":a.permission).put("permissionGranted",a.permission==null||c.checkCallingOrSelfPermission(a.permission)==PackageManager.PERMISSION_GRANTED);}
 static JSONObject file(String path)throws JSONException {File f=new File(path);try{return new JSONObject().put("path",path).put("exists",f.exists()).put("readable",f.canRead()).put("writable",f.canWrite());}catch(SecurityException e){return new JSONObject().put("path",path).put("status","denied");}}
}
