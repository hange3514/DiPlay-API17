package local.headunit.diplay;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.hardware.usb.*;
import android.media.*;
import android.os.*;
import android.util.DisplayMetrics;
import java.io.*;
import java.util.*;
import org.json.*;

/** Passive evidence only: no USB claim/reset, codec allocation, audio focus request or vehicle writes. */
final class EnvironmentCheck {
 interface Check {JSONObject run() throws Exception;}
 static void section(JSONObject report,String name,Check check)throws JSONException {
  long start=SystemClock.elapsedRealtime();JSONObject result;
  try{result=check.run();if(!result.has("status"))result.put("status","measured");}
  catch(SecurityException e){result=new JSONObject().put("status","denied").put("error",e.getClass().getSimpleName());}
  catch(Exception e){result=new JSONObject().put("status","unavailable").put("error",e.getClass().getSimpleName());}
  result.put("durationMs",SystemClock.elapsedRealtime()-start);report.put(name,result);
 }
 static JSONObject collect(final Context context,JSONObject ui)throws Exception {
  final Context c=context.getApplicationContext();JSONObject report=new JSONObject().put("schema",1).put("checkId",UUID.randomUUID().toString()).put("time",System.currentTimeMillis()).put("passive",true).put("ui",ui);
  section(report,"rootReadiness",new Check(){public JSONObject run()throws Exception{return RootReadiness.collect();}});
  section(report,"platform",new Check(){public JSONObject run()throws Exception{return new JSONObject().put("sdk",Build.VERSION.SDK_INT).put("release",Build.VERSION.RELEASE).put("model",Build.MODEL).put("manufacturer",Build.MANUFACTURER).put("board",Build.BOARD).put("hardware",Build.HARDWARE).put("abi",Build.CPU_ABI).put("abi2",Build.CPU_ABI2).put("build",Build.DISPLAY).put("uptimeMs",SystemClock.elapsedRealtime()).put("cpuCores",Runtime.getRuntime().availableProcessors()).put("cpu0FrequencyKHz",UsbPresenceLog.read(new File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"))).put("appVersion",c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName);}});
  section(report,"usb",new Check(){public JSONObject run()throws Exception{
   UsbManager manager=(UsbManager)c.getSystemService(Context.USB_SERVICE);JSONArray devices=new JSONArray(),nodes=new JSONArray();
   for(UsbDevice d:manager.getDeviceList().values()){if(devices.length()>=32)break;JSONArray interfaces=new JSONArray();for(int n=0;n<Math.min(d.getInterfaceCount(),32);n++){UsbInterface f=d.getInterface(n);JSONArray endpoints=new JSONArray();for(int k=0;k<Math.min(f.getEndpointCount(),32);k++){UsbEndpoint e=f.getEndpoint(k);endpoints.put(new JSONObject().put("address",e.getAddress()).put("type",e.getType()).put("maxPacket",e.getMaxPacketSize()).put("interval",e.getInterval()));}interfaces.put(new JSONObject().put("id",f.getId()).put("class",f.getInterfaceClass()).put("subclass",f.getInterfaceSubclass()).put("protocol",f.getInterfaceProtocol()).put("endpoints",endpoints));}devices.put(new JSONObject().put("vid",d.getVendorId()).put("pid",d.getProductId()).put("permission",manager.hasPermission(d)).put("interfaces",interfaces));}
   File[] files=new File("/sys/bus/usb/devices").listFiles();int visited=0;if(files!=null)for(File f:files){if(++visited>128||nodes.length()>=32)break;String vid=UsbPresenceLog.read(new File(f,"idVendor"));if(vid.matches("[a-fA-F0-9]{4}"))nodes.put(new JSONObject().put("node",f.getName()).put("vid",vid).put("pid",UsbPresenceLog.read(new File(f,"idProduct"))).put("speedMbps",UsbPresenceLog.read(new File(f,"speed"))).put("configuration",UsbPresenceLog.read(new File(f,"bConfigurationValue"))).put("usbVersion",UsbPresenceLog.read(new File(f,"version"))));}
   return new JSONObject().put("devices",devices).put("sysfsStatus",files==null?"unavailable_or_denied":"measured").put("kernel",nodes).put("interfaceBindings",usbBindings(files)).put("phonePresent",hasPhone(manager)).put("bandwidthTest","not_run_passive_only");
  }});
  section(report,"avcDecoders",new Check(){public JSONObject run()throws Exception{JSONArray decoders=new JSONArray();int count=MediaCodecList.getCodecCount();for(int n=0;n<Math.min(count,128);n++){MediaCodecInfo info=MediaCodecList.getCodecInfoAt(n);if(info.isEncoder())continue;for(String type:info.getSupportedTypes())if("video/avc".equalsIgnoreCase(type)){JSONObject d=new JSONObject().put("name",info.getName());try{MediaCodecInfo.CodecCapabilities cap=info.getCapabilitiesForType(type);JSONArray profiles=new JSONArray();for(int k=0;k<Math.min(cap.profileLevels.length,64);k++)profiles.put(new JSONObject().put("profile",cap.profileLevels[k].profile).put("level",cap.profileLevels[k].level));d.put("profiles",profiles).put("status","advertised");}catch(Exception e){d.put("status","unavailable").put("error",e.getClass().getSimpleName());}decoders.put(d);}}return new JSONObject().put("decoders",decoders).put("allocationTest","not_run_avoid_competing_with_projection");}});
  section(report,"audio",new Check(){public JSONObject run()throws Exception{AudioManager a=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);return new JSONObject().put("mode",a.getMode()).put("musicActive",a.isMusicActive()).put("musicVolume",a.getStreamVolume(AudioManager.STREAM_MUSIC)).put("musicMaxVolume",a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).put("speaker",a.isSpeakerphoneOn()).put("bluetoothSco",a.isBluetoothScoOn()).put("bluetoothA2dp",a.isBluetoothA2dpOn()).put("wiredHeadset",a.isWiredHeadsetOn()).put("microphoneMuted",a.isMicrophoneMute()).put("focusOwner","not_exposed_by_public_api").put("audibleOutput","requires_user_confirmation").put("originalBluetoothService",packageVersion(c,"com.byd.btservice"));}});
  section(report,"audioRouting",new Check(){public JSONObject run()throws Exception{return AudioRouting.evidence(c);}});
  section(report,"audioPlatformEvidence",new Check(){public JSONObject run(){return AudioSystemEvidence.environment();}});
  section(report,"alsaCaptureEnvironment",new Check(){public JSONObject run(){return AlsaCapturePathSelector.environment(c);}});
  section(report,"oemCallAudioApks",new Check(){public JSONObject run(){return DeviceApkEvidence.collect(context);}});
  section(report,"audioBuffers",new Check(){public JSONObject run()throws Exception{
   JSONArray formats=new JSONArray();for(int rate:new int[]{16000,48000})for(int channels:new int[]{1,2}){
    int mask=channels==1?android.media.AudioFormat.CHANNEL_OUT_MONO:android.media.AudioFormat.CHANNEL_OUT_STEREO;
    int minimum=AudioTrack.getMinBufferSize(rate,mask,android.media.AudioFormat.ENCODING_PCM_16BIT);
    JSONObject format=new JSONObject().put("sampleRate",rate).put("channels",channels).put("minimumOutputBytes",minimum).put("status",minimum>0?"advertised":"unsupported_or_error");
    if(minimum>0){int chosen=Math.max(minimum*2,rate*channels/5);format.put("configuredOutputBytes",chosen).put("configuredCapacityMs",chosen*1000L/(rate*channels*2));}formats.put(format);
   }
   return new JSONObject().put("formats",formats).put("scope","capacity_query_only_not_measured_playback_latency");
  }});
  section(report,"process",new Check(){public JSONObject run()throws Exception{Runtime r=Runtime.getRuntime();ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();((ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(m);String[] fds=new File("/proc/self/fd").list();JSONArray threads=new JSONArray();int count=0;for(Map.Entry<Thread,StackTraceElement[]> entry:Thread.getAllStackTraces().entrySet()){Thread t=entry.getKey();count++;if(threads.length()<96){JSONObject thread=new JSONObject().put("name",t.getName()).put("state",t.getState().name());String name=t.getName();if(name.startsWith("legacy-")||name.startsWith("api17-")||name.startsWith("airplay-")||name.startsWith("ncm-")||name.startsWith("diplay-")){JSONArray stack=new JSONArray();StackTraceElement[] trace=entry.getValue();for(int k=0;k<Math.min(trace.length,6);k++)stack.put(trace[k].getClassName()+"#"+trace[k].getMethodName()+":"+trace[k].getLineNumber());thread.put("stack",stack);}threads.put(thread);}}return new JSONObject().put("fdStatus",fds==null?"unavailable_or_denied":"measured").put("fdCount",fds==null?-1:fds.length).put("threadCount",count).put("threads",threads).put("heapUsedBytes",r.totalMemory()-r.freeMemory()).put("heapMaxBytes",r.maxMemory()).put("nativeHeapBytes",Debug.getNativeHeapAllocatedSize()).put("systemAvailableBytes",m.availMem).put("lowMemory",m.lowMemory).put("connectionBusy",WiredService.isBusy()).put("usbLinked",WiredService.hasUsbLink()).put("mediaWorkersBusy",MediaWorkers.busy()).put("usbPermissionPending",WiredService.awaitingPermission());}});
  section(report,"storage",new Check(){public JSONObject run()throws Exception{return new JSONObject().put("internal",storage(c.getFilesDir())).put("updates",storage(c.getExternalFilesDir("updates"))).put("externalState",Environment.getExternalStorageState());}});
  section(report,"display",new Check(){public JSONObject run()throws Exception{DisplayMetrics d=c.getResources().getDisplayMetrics();return new JSONObject().put("widthPixels",d.widthPixels).put("heightPixels",d.heightPixels).put("density",d.density).put("densityDpi",d.densityDpi);}});
  section(report,"automationCapabilities",new Check(){public JSONObject run()throws Exception{return AutomationEvidence.collect(c);}});
  section(report,"settingsImplementation",new Check(){public JSONObject run()throws Exception{return AutomationEvidence.settingsClasses(c);}});
  section(report,"playbackOptions",new Check(){public JSONObject run()throws Exception{return new JSONObject().put("videoDecryptBackend",NativeCrypto.available?"Monocypher-4.0.2-native":"BouncyCastle-Java").put("nextConnectionFps",PlaybackOptions.read(c)).put("currentConnectionFps",WiredService.currentFps()).put("debugOverlay",c.getSharedPreferences("playback",0).getBoolean("debug_fps",false)).put("renderedFrames",WiredService.renderedVideoFrames()).put("counterScope","frames_submitted_to_surface_not_physical_display_refresh");}});
  section(report,"connection",new Check(){public JSONObject run()throws Exception{return WiredService.diagnosticState();}});
  section(report,"connectionPermissions",new Check(){public JSONObject run()throws Exception{return usbHandlers(c).put("vpnApprovalRequired",android.net.VpnService.prepare(c)!=null).put("uptimeMs",SystemClock.elapsedRealtime()).put("lockdownPairSaved",c.getSharedPreferences("private_pairing",0).contains("lockdown"));}});
  report.put("connectionPerformance",new JSONObject().put("status",WiredService.hasUsbLink()?"see_attached_session_events":"requires_active_connection").put("events","CONNECT_TIMING, USB_NATIVE, Receive, video_progress, AUDIO_FOCUS, release"));
  report.put("limits",new JSONArray().put("No automatic USB reset or interface claim").put("No audio route/focus changes or codec allocation").put("Playback audibility and phone approval need real-device observation").put("Resource leaks require comparing successive connection lifecycles"));
  return report;
 }
 static JSONObject usbHandlers(Context c)throws Exception {
  android.content.pm.PackageManager pm=c.getPackageManager();
  java.util.List<android.content.pm.ResolveInfo> matches=pm.queryIntentActivities(new android.content.Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED),android.content.pm.PackageManager.GET_META_DATA);
  JSONArray handlers=new JSONArray();
  for(android.content.pm.ResolveInfo match:matches){
   if(handlers.length()>=32)break;
   android.content.pm.ActivityInfo a=match.activityInfo;if(a==null)continue;
   JSONObject item=new JSONObject().put("package",a.packageName).put("activity",a.name);JSONArray filters=new JSONArray();
   android.content.res.XmlResourceParser xml=null;
   try{xml=a.loadXmlMetaData(pm,UsbManager.ACTION_USB_DEVICE_ATTACHED);if(xml!=null){int event;int tokens=0;while((event=xml.next())!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT&&++tokens<1024&&filters.length()<32){if(event!=org.xmlpull.v1.XmlPullParser.START_TAG||!"usb-device".equals(xml.getName()))continue;JSONObject filter=new JSONObject();for(String key:new String[]{"vendor-id","product-id","class","subclass","protocol"}){String value=xml.getAttributeValue(null,key);if(value!=null)filter.put(key,value);}filters.put(filter);}}}catch(Exception e){item.put("filterError",e.getClass().getSimpleName());}finally{if(xml!=null)xml.close();}
   handlers.put(item.put("filters",filters));
  }
  return new JSONObject().put("usbHandlerCandidates",handlers).put("scope","declared_candidates_only_not_proof_of_current_usb_owner").put("truncated",matches.size()>handlers.length());
 }
 static void server(final MainActivity a,JSONObject report)throws Exception {
  section(report,"updateAndLogServer",new Check(){public JSONObject run()throws Exception{
   java.net.HttpURLConnection c=OnlineUpdate.open(a,"/updates/diplay/latest");c.setConnectTimeout(5000);c.setReadTimeout(5000);NetworkDeadline deadline=new NetworkDeadline(c,10000);
   try{int code=c.getResponseCode();deadline.check();return new JSONObject().put("status",code==200?"reachable":"http_error").put("httpStatus",code).put("scope","update_and_log_only_not_carplay_media");}finally{deadline.close();}
  }});
 }
 static void record(Context c,JSONObject report)throws Exception {
  // The shared server accepts at most 3000 characters per event. Base64 keeps each
  // independently numbered fragment below that bound regardless of UTF-8 or JSON escaping.
  byte[] bytes=report.toString().getBytes("UTF-8");if(bytes.length>64000)throw new IOException("Environment report exceeds local bound");
  int parts=(bytes.length+1535)/1536;String id=report.getString("checkId");
  MainActivity.record(c,"ENVIRONMENT_CHECK",new JSONObject().put("checkId",id).put("encoding","utf8-base64-parts").put("parts",parts).put("bytes",bytes.length));
  for(int n=0;n<parts;n++){int offset=n*1536;String data=android.util.Base64.encodeToString(bytes,offset,Math.min(1536,bytes.length-offset),android.util.Base64.NO_WRAP);MainActivity.record(c,"ENVIRONMENT_CHECK_PART",new JSONObject().put("checkId",id).put("part",n).put("parts",parts).put("data",data));}
 }
 static JSONObject usbBindings(File[] files)throws JSONException {
  JSONArray bindings=new JSONArray();if(files==null)return new JSONObject().put("status","unavailable_or_denied").put("interfaces",bindings);
  int visited=0;for(File node:files){if(visited>=128||bindings.length()>=64)break;visited++;if(node.getName().indexOf(':')<0)continue;
   JSONObject info=new JSONObject().put("node",node.getName()).put("interfaceNumber",UsbPresenceLog.read(new File(node,"bInterfaceNumber"))).put("alternate",UsbPresenceLog.read(new File(node,"bAlternateSetting"))).put("class",UsbPresenceLog.read(new File(node,"bInterfaceClass")));
   File driver=new File(node,"driver");try{if(driver.exists())info.put("driverStatus","resolved").put("driverName",driver.getCanonicalFile().getName());else info.put("driverStatus","unbound_or_unreadable");}catch(SecurityException e){info.put("driverStatus","denied");}catch(IOException e){info.put("driverStatus","unavailable");}
   bindings.put(info);
  }
  return new JSONObject().put("status","measured").put("interfaces",bindings).put("truncated",visited<files.length).put("ownerProcess","not_identified_by_sysfs_driver_name");
 }
 static JSONObject storage(File folder)throws JSONException {if(folder==null)return new JSONObject().put("status","unavailable");long total=folder.getTotalSpace();return new JSONObject().put("status",total>0?"measured":"capacity_unavailable").put("totalBytes",total).put("usableBytes",folder.getUsableSpace()).put("writable",folder.canWrite());}
 static boolean hasPhone(UsbManager m){for(UsbDevice d:m.getDeviceList().values())if(d.getVendorId()==0x05ac)return true;return false;}
 static JSONObject packageVersion(Context c,String name)throws Exception{try{PackageInfo p=c.getPackageManager().getPackageInfo(name,0);return new JSONObject().put("status","measured").put("code",p.versionCode).put("version",p.versionName);}catch(android.content.pm.PackageManager.NameNotFoundException e){return new JSONObject().put("status","not_installed");}}
}
