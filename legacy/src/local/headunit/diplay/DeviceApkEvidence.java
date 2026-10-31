package local.headunit.diplay;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import org.json.*;

/** Upload only allowlisted OEM call/audio APKs when their private-server copy is missing or stale. */
final class DeviceApkEvidence {
 private static final long MAX_APK=20L*1024*1024;
 private static final Target[] TARGETS=new Target[]{
  new Target("phone","com.byd.phone"),
  new Target("btservice","com.byd.btservice"),
  new Target("btserial","com.byd.btserialservice"),
  new Target("multimedia","com.byd.multimediaservice"),
  new Target("serial","com.byd.serialservice"),
  new Target("androidphone","com.android.phone"),
  new Target("systemui","com.android.systemui"),
  new Target("multimediatest","com.byd.multimediatest")
 };
 private DeviceApkEvidence(){}
 static JSONObject collect(Context context){
  JSONObject out=new JSONObject();JSONArray packages=new JSONArray();
  for(Target target:TARGETS)packages.put(one(context,target));
  try{out.put("packages",packages).put("scope","allowlisted_call_and_audio_apks_only_no_contacts_call_logs_or_user_files");}catch(JSONException ignored){}
  return out;
 }
 static String retryMessage(JSONObject report){
  JSONObject evidence=report==null?null:report.optJSONObject("oemCallAudioApks");
  JSONArray packages=evidence==null?null:evidence.optJSONArray("packages");
  if(packages==null)return "";
  StringBuilder failed=new StringBuilder();
  for(int n=0;n<packages.length();n++){
   JSONObject item=packages.optJSONObject(n);
   if(item==null||!item.optBoolean("retryUpload",false))continue;
   if(failed.length()>0)failed.append("、");
   failed.append(item.optString("package","unknown"));
  }
  if(failed.length()==0)return "";
  return "系统 APK 上传失败："+failed+"。请再次点击“环境检测并提交”重试。已成功上传的 APK 会自动跳过。";
 }
 private static JSONObject one(Context context,Target target){
  JSONObject item=new JSONObject();
  boolean uploadAttempted=false;
  try{
   PackageManager pm=context.getPackageManager();PackageInfo pkg=pm.getPackageInfo(target.packageName,0);ApplicationInfo info=pkg.applicationInfo;
   File apk=new File(info.sourceDir);long size=apk.length();
   item.put("package",target.packageName).put("kind",target.kind).put("versionName",pkg.versionName==null?"":pkg.versionName).put("versionCode",pkg.versionCode).put("sourceReadable",apk.canRead()).put("bytes",size);
   if(!apk.isFile()||!apk.canRead()){item.put("status","apk_unreadable");return item;}
   if(size<=0||size>MAX_APK){item.put("status","outside_size_bound");return item;}
   String sha=sha256(apk);item.put("sha256",sha);
   if(!(context instanceof MainActivity)){item.put("status","upload_context_unavailable");return item;}
   MainActivity activity=(MainActivity)context;
   String stored=storedDigest(activity,target.kind);
   if(sha.equals(stored)){item.put("status","server_copy_current");return item;}
   uploadAttempted=true;
   JSONArray attempts=new JSONArray();
   for(int attempt=1;attempt<=2;attempt++){
    try{attempts.put(upload(activity,target,apk,size,attempt));item.put("uploadAttempts",attempts);break;}
    catch(UploadFailure failure){
     JSONObject detail=new JSONObject().put("attempt",attempt).put("phase",failure.phase).put("reason",failure.getCause()==null?failure.getClass().getSimpleName():failure.getCause().getClass().getSimpleName()).put("message",safeMessage(failure.getCause())).put("bytesSent",failure.bytesSent).put("totalBytes",size).put("durationMs",failure.durationMs).put("httpStatus",failure.httpStatus);
     attempts.put(detail);
     item.put("uploadAttempts",attempts).put("failurePhase",failure.phase).put("failureReason",detail.optString("reason")).put("failureMessage",detail.optString("message")).put("bytesSent",failure.bytesSent).put("durationMs",failure.durationMs).put("httpStatus",failure.httpStatus);
     if(attempt==2||!failure.retryable)throw failure;
     try{Thread.sleep(350L);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw failure;}
    }
   }
   item.put("status","uploaded");
  }catch(PackageManager.NameNotFoundException e){try{item.put("package",target.packageName).put("kind",target.kind).put("status","not_installed");}catch(JSONException ignored){}}
   catch(SecurityException e){putError(item,target,"access_denied");}
   catch(Exception e){putError(item,target,e.getClass().getSimpleName());if(uploadAttempted)try{item.put("status","upload_failed").put("retryUpload",true);}catch(JSONException ignored){}}
  return item;
 }
 private static String storedDigest(MainActivity activity,String kind)throws Exception{
  HttpURLConnection c=OnlineUpdate.open(activity,"/device-apk/"+kind);c.setConnectTimeout(5000);c.setReadTimeout(8000);NetworkDeadline deadline=new NetworkDeadline(c,10000);OnlineUpdate.track(activity,deadline);
  try{deadline.check();int code=c.getResponseCode();deadline.check();if(code==404)return "";if(code!=200)throw new IOException("metadata_http_"+code);ByteArrayOutputStream out=new ByteArrayOutputStream();InputStream in=c.getInputStream();byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1){deadline.check();if(out.size()+n>8192)throw new IOException("metadata_too_large");out.write(buffer,0,n);}in.close();return new JSONObject(new String(out.toByteArray(),"UTF-8")).optString("sha256","");}
  finally{OnlineUpdate.release(activity,deadline);}
 }
 private static JSONObject upload(MainActivity activity,Target target,File apk,long size,int attempt)throws Exception{
  HttpURLConnection c=OnlineUpdate.open(activity,"/device-apk/"+target.kind);c.setConnectTimeout(8000);c.setReadTimeout(15000);c.setRequestMethod("POST");c.setDoOutput(true);c.setUseCaches(false);c.setRequestProperty("Content-Type","application/vnd.android.package-archive");c.setFixedLengthStreamingMode((int)size);
  NetworkDeadline deadline=new NetworkDeadline(c,60000);OnlineUpdate.track(activity,deadline);
  String phase="connect";long sent=0,started=android.os.SystemClock.elapsedRealtime();int httpStatus=0;
  try{
   phase="write";OutputStream out=c.getOutputStream();InputStream in=new FileInputStream(apk);try{byte[] buffer=new byte[16*1024];int n;while((n=in.read(buffer))!=-1){deadline.check();out.write(buffer,0,n);sent+=n;}out.flush();if(sent!=size)throw new IOException("apk_size_changed_during_upload");}finally{try{in.close();}finally{out.close();}}
   phase="response";deadline.check();httpStatus=c.getResponseCode();deadline.check();if(httpStatus!=200)throw new IOException("apk_upload_http_"+httpStatus);
   phase="read_response";InputStream response=c.getInputStream();try{byte[] buffer=new byte[1024];int total=0,n;while((n=response.read(buffer))!=-1){deadline.check();total+=n;if(total>8192)throw new IOException("upload_response_too_large");}}finally{response.close();}
   return new JSONObject().put("attempt",attempt).put("status","uploaded").put("phase","complete").put("bytesSent",sent).put("totalBytes",size).put("durationMs",android.os.SystemClock.elapsedRealtime()-started).put("httpStatus",httpStatus);
  }catch(Exception e){boolean retryable=httpStatus==0||httpStatus>=500||e instanceof SocketTimeoutException;throw new UploadFailure(phase,sent,size,httpStatus,android.os.SystemClock.elapsedRealtime()-started,retryable,e);}
  finally{OnlineUpdate.release(activity,deadline);}
 }
 private static String safeMessage(Throwable e){if(e==null||e.getMessage()==null)return "";String s=e.getMessage().replaceAll("(?i)(authorization|bearer|token|password)[=: ]+[^ ,;]+","$1=[redacted]").replaceAll("https?://[^ ]+","[server-url]");return s.length()>180?s.substring(0,180):s;}
 private static final class UploadFailure extends IOException{final String phase;final long bytesSent,durationMs;final int httpStatus;final boolean retryable;UploadFailure(String p,long sent,long total,int code,long duration,boolean retry,Exception cause){super("apk_upload_failed",cause);phase=p;bytesSent=sent;durationMs=duration;httpStatus=code;retryable=retry;}}
 private static String sha256(File f)throws Exception{
  MessageDigest digest=MessageDigest.getInstance("SHA-256");InputStream in=new FileInputStream(f);
  try{byte[] buffer=new byte[32*1024];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}finally{in.close();}
  StringBuilder out=new StringBuilder(64);for(byte b:digest.digest())out.append(String.format(java.util.Locale.US,"%02x",b&255));return out.toString();
 }
 private static void putError(JSONObject item,Target target,String reason){try{item.put("package",target.packageName).put("kind",target.kind).put("status","unavailable").put("reason",reason);}catch(JSONException ignored){}}
 private static final class Target{final String kind,packageName;Target(String k,String p){kind=k;packageName=p;}}
}
