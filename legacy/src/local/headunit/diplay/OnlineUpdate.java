package local.headunit.diplay;
import android.app.*;import android.content.*;import android.content.pm.*;import android.net.Uri;import java.io.*;import java.net.*;import java.security.*;import java.util.*;import org.json.*;
/** Dedicated release channel; integrity, identity and version checked before invoking installer. */
final class OnlineUpdate {
 static final int MAX_APK=20*1024*1024;
 static final long MAX_MANAGER_APK=64L*1024*1024;
 private static final String MANAGER_SHA="9466b97a1f6dc6cfc351e85947d28b98c650b096cd9c0bd1afffdfb188041485";
 private static final long MANAGER_SIZE=21005L;
 private static final java.util.concurrent.atomic.AtomicBoolean downloadRunning=new java.util.concurrent.atomic.AtomicBoolean();
 static void track(MainActivity a,NetworkDeadline request){a.updateRequest=request;if(a.closed)request.cancel();}
 static void release(MainActivity a,NetworkDeadline request){request.close();if(a.updateRequest==request)a.updateRequest=null;}
 static void cancel(MainActivity a){NetworkDeadline request=a.updateRequest;if(request!=null)request.cancel();}

 static void check(final MainActivity a){check(a,false);}
 static void rollback(final MainActivity a){check(a,true);}
 static void check(final MainActivity a,final boolean rollback){if(a.busy||a.connecting||a.closed)return;a.busy=true;a.showOperation(rollback?"正在检查0.34有线稳定版…":"正在检查DiPlay更新…");a.worker.execute(new Runnable(){public void run(){try{
  HttpURLConnection c=open(a,"/updates/diplay/latest");NetworkDeadline deadline=new NetworkDeadline(c,30000);track(a,deadline);JSONObject j;try{deadline.check();ByteArrayOutputStream data=new ByteArrayOutputStream();InputStream in=c.getInputStream();byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){deadline.check();if(a.closed)throw new IOException("页面已关闭");if(data.size()+n>32768)throw new IOException("版本信息过大");data.write(b,0,n);}in.close();j=new JSONObject(new String(data.toByteArray(),"UTF-8"));deadline.check();}finally{release(a,deadline);}
  if(rollback){
   JSONObject stable=j.optJSONObject("rollback");
   if(stable==null||!"0.34".equals(stable.optString("baseline")))throw new IOException("服务器尚未准备有线稳定版");
   if(stable.optInt("versionCode",-1)!=j.optInt("versionCode",-2))throw new IOException("稳定版与实验版安装编号不匹配");
   j=stable;
  }
  final PackageInfo installed=a.getPackageManager().getPackageInfo(a.getPackageName(),0);
  if(!j.optBoolean("available")){done(a,"服务器暂无DiPlay版本。");return;}
  final int code=j.getInt("versionCode");final String name=j.getString("versionName"),sha=j.getString("sha256");final long size=j.getLong("size");final String notes=j.optString("notes","");
  if(!sha.matches("[a-f0-9]{64}")||size<1||size>MAX_APK||name.length()>100||notes.length()>6000)throw new IOException("版本信息无效");
  if(code<installed.versionCode){done(a,"当前版本比服务器版本新，无需更新。");return;}
  a.runOnUiThread(new Runnable(){public void run(){a.busy=false;if(a.closed)return;String title=rollback?"回退到0.34有线稳定版":code==installed.versionCode?"已是最新版本":"发现DiPlay新版本";a.showOperation(title+"："+name);a.maintenance=true;AlertDialog dialog=new AlertDialog.Builder(a).setTitle(title).setMessage((rollback?"恢复已实车验证的0.34有线功能，保留应用数据。安装编号仅用于兼容系统覆盖安装；回退后仍可通过在线更新返回实验版。\n\n":"")+"当前："+installed.versionName+"\n服务器："+name+"\n大小："+(size/1024)+" KB\n\n"+notes+"\n\n下载校验后将打开系统安装界面，请停车后操作。").setNegativeButton("取消",null).setPositiveButton(rollback?"下载有线稳定版":code==installed.versionCode?"重新安装本版":"下载更新",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){download(a,code,sha,size);}}).create();dialog.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){a.maintenance=false;}});dialog.show();}});
 }catch(Exception e){error(a,"检查更新",e);}}});}
 static HttpURLConnection open(MainActivity a,String path)throws Exception {HttpURLConnection c=(HttpURLConnection)new URL(a.asset("server.txt")+path).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestProperty("Connection","close");c.setRequestProperty("Authorization","Bearer "+a.asset("token.txt"));return c;}
 static void download(final MainActivity a,final int code,final String sha,final long size){if(a.busy||a.connecting||a.closed)return;if(!downloadRunning.compareAndSet(false,true)){a.showOperation("上一次下载正在结束，请稍后再试。");return;}a.busy=true;a.showOperation("正在下载更新…");try{a.worker.execute(new Runnable(){public void run(){File part=null;try{
  File folder=a.getExternalFilesDir("updates");part=UpdateFiles.part(folder);UpdateFiles.expire(folder,System.currentTimeMillis());UpdateFiles.requireSpace(folder,size);final File target=UpdateFiles.target(folder,sha);MessageDigest hash=MessageDigest.getInstance("SHA-256");HttpURLConnection c=open(a,"/updates/diplay/apk/"+sha);NetworkDeadline deadline=new NetworkDeadline(c,120000);track(a,deadline);long count=0,previous=0;
  try{deadline.check();if(c.getResponseCode()!=200)throw new IOException("下载HTTP "+c.getResponseCode());InputStream in=c.getInputStream();try{FileOutputStream out=new FileOutputStream(part);try{byte[] buf=new byte[16384];int n;while((n=in.read(buf))!=-1){deadline.check();if(a.closed)throw new IOException("页面已关闭，下载取消");count+=n;if(count>size||count>MAX_APK)throw new IOException("安装包大小不符");out.write(buf,0,n);hash.update(buf,0,n);long now=System.currentTimeMillis();if(now-previous>500){previous=now;final long progress=count;a.runOnUiThread(new Runnable(){public void run(){a.showOperation("正在下载更新："+(progress*100/size)+"%");}});}}deadline.check();out.getFD().sync();}finally{out.close();}}finally{in.close();}}finally{release(a,deadline);}
  if(count!=size||!sha.equals(hex(hash.digest())))throw new IOException("安装包完整性校验失败");
  PackageManager pm=a.getPackageManager();PackageInfo archive=pm.getPackageArchiveInfo(part.getAbsolutePath(),PackageManager.GET_SIGNATURES);PackageInfo current=pm.getPackageInfo(a.getPackageName(),PackageManager.GET_SIGNATURES);
  verifyPackage(archive,current,code);
  if(a.closed)throw new IOException("页面已关闭，下载取消");UpdateFiles.promote(part,target);target.setReadable(true,false);
  MainActivity.record(a,"update_verified",new JSONObject().put("versionCode",code).put("sha256",sha));
  a.runOnUiThread(new Runnable(){public void run(){a.busy=false;if(a.closed)return;try{a.maintenance=true;AutoUsbService.beginMaintenance(a);Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(target),"application/vnd.android.package-archive");a.startActivityForResult(i,20);a.showOperation("已校验并打开系统安装界面，请点击安装。安装后重新打开DiPlay。");}catch(Exception e){a.maintenance=false;AutoUsbService.endMaintenance(a);a.showOperation("无法打开系统安装器："+e.getClass().getSimpleName());}}});
 }catch(Exception e){error(a,"下载更新",e);}finally{try{if(part!=null&&part.exists())part.delete();}finally{downloadRunning.set(false);}}}});}catch(java.util.concurrent.RejectedExecutionException e){downloadRunning.set(false);a.busy=false;error(a,"下载更新",e);}}
 static void installVersionManager(final MainActivity a){
  if(a.busy||a.connecting||a.closed)return;
  new AlertDialog.Builder(a).setTitle("\u5b89\u88c5\u7248\u672c\u7ba1\u7406\u5668").setMessage("\u5c06\u4e0b\u8f7d\u72ec\u7acb\u7684\u5386\u53f2\u7248\u672c\u7ba1\u7406\u5668\uff08\u7ea6 "+(MANAGER_SIZE/1024)+" KB\uff09\u3002\u4e0b\u8f7d\u540e\u4f1a\u6821\u9a8c SHA-256\u3001\u5305\u540d\u548c\u7b7e\u540d\uff0c\u518d\u6253\u5f00\u7cfb\u7edf\u5b89\u88c5\u754c\u9762\u3002\u8bf7\u505c\u8f66\u540e\u64cd\u4f5c\u3002")
   .setNegativeButton("\u53d6\u6d88",null).setPositiveButton("\u4e0b\u8f7d\u5e76\u5b89\u88c5",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){downloadManager(a);}}).show();
 }
 static void downloadManager(final MainActivity a){
  if(a.busy||a.connecting||a.closed)return;
  if(!downloadRunning.compareAndSet(false,true)){a.showOperation("\u4e0a\u4e00\u6b21\u4e0b\u8f7d\u6b63\u5728\u7ed3\u675f\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002");return;}
  a.busy=true;a.showOperation("\u6b63\u5728\u4e0b\u8f7d\u7248\u672c\u7ba1\u7406\u5668\u2026");
  try{a.worker.execute(new Runnable(){public void run(){File part=null;try{
   File folder=a.getExternalFilesDir("updates");part=UpdateFiles.part(folder);UpdateFiles.expire(folder,System.currentTimeMillis());UpdateFiles.requireSpace(folder,MANAGER_SIZE,MAX_MANAGER_APK);final File target=UpdateFiles.target(folder,MANAGER_SHA);
   MessageDigest hash=MessageDigest.getInstance("SHA-256");HttpURLConnection c=open(a,"/updates/diplay/apk/"+MANAGER_SHA);NetworkDeadline deadline=new NetworkDeadline(c,180000);track(a,deadline);long count=0,previous=0;
   try{deadline.check();if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());InputStream in=c.getInputStream();try{FileOutputStream out=new FileOutputStream(part);try{byte[] buf=new byte[16384];int n;while((n=in.read(buf))!=-1){deadline.check();if(a.closed)throw new IOException("\u9875\u9762\u5df2\u5173\u95ed\uff0c\u4e0b\u8f7d\u53d6\u6d88");count+=n;if(count>MANAGER_SIZE||count>MAX_MANAGER_APK)throw new IOException("\u7ba1\u7406\u5668\u5b89\u88c5\u5305\u5927\u5c0f\u4e0d\u7b26");out.write(buf,0,n);hash.update(buf,0,n);long now=System.currentTimeMillis();if(now-previous>500){previous=now;final long progress=count;a.runOnUiThread(new Runnable(){public void run(){a.showOperation("\u6b63\u5728\u4e0b\u8f7d\u7248\u672c\u7ba1\u7406\u5668\uff1a"+(progress*100/MANAGER_SIZE)+"%");}});}}deadline.check();out.getFD().sync();}finally{out.close();}}finally{in.close();}}finally{release(a,deadline);}
   if(count!=MANAGER_SIZE||!MANAGER_SHA.equals(hex(hash.digest())))throw new IOException("\u7248\u672c\u7ba1\u7406\u5668\u5b8c\u6574\u6027\u6821\u9a8c\u5931\u8d25");
   PackageManager pm=a.getPackageManager();PackageInfo archive=pm.getPackageArchiveInfo(part.getAbsolutePath(),PackageManager.GET_SIGNATURES);PackageInfo current=pm.getPackageInfo(a.getPackageName(),PackageManager.GET_SIGNATURES);
   if(archive==null||!"local.headunit.diplay.versionmanager".equals(archive.packageName)||archive.versionCode!=2||current==null||archive.signatures==null||current.signatures==null||archive.signatures.length==0||!new HashSet<android.content.pm.Signature>(Arrays.asList(archive.signatures)).equals(new HashSet<android.content.pm.Signature>(Arrays.asList(current.signatures))))throw new IOException("\u7248\u672c\u7ba1\u7406\u5668\u5305\u540d\u6216\u7b7e\u540d\u6821\u9a8c\u5931\u8d25");
   if(a.closed)throw new IOException("\u9875\u9762\u5df2\u5173\u95ed\uff0c\u4e0b\u8f7d\u53d6\u6d88");UpdateFiles.promote(part,target);target.setReadable(true,false);
   MainActivity.record(a,"version_manager_verified",new JSONObject().put("sha256",MANAGER_SHA).put("size",MANAGER_SIZE));
   a.runOnUiThread(new Runnable(){public void run(){a.busy=false;if(a.closed)return;try{a.maintenance=true;AutoUsbService.beginMaintenance(a);Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(target),"application/vnd.android.package-archive");a.startActivityForResult(i,21);a.showOperation("\u7248\u672c\u7ba1\u7406\u5668\u5df2\u6821\u9a8c\uff0c\u5df2\u6253\u5f00\u7cfb\u7edf\u5b89\u88c5\u754c\u9762\u3002\u8bf7\u786e\u8ba4\u5b89\u88c5\uff1b\u5b89\u88c5\u540e\u53ef\u4ece\u5e94\u7528\u5217\u8868\u6253\u5f00\u3002");}catch(Exception e){a.maintenance=false;AutoUsbService.endMaintenance(a);a.showOperation("\u65e0\u6cd5\u6253\u5f00\u7cfb\u7edf\u5b89\u88c5\u5668\uff1a"+e.getClass().getSimpleName());}}});
  }catch(Exception e){error(a,"\u4e0b\u8f7d\u7248\u672c\u7ba1\u7406\u5668",e);}finally{try{if(part!=null&&part.exists())part.delete();}finally{downloadRunning.set(false);}}}});}catch(java.util.concurrent.RejectedExecutionException e){downloadRunning.set(false);a.busy=false;error(a,"\u4e0b\u8f7d\u7248\u672c\u7ba1\u7406\u5668",e);}
 }
 static void verifyPackage(PackageInfo archive,PackageInfo current,int code)throws IOException {
  if(archive==null||current==null||current.packageName==null||!current.packageName.equals(archive.packageName)||archive.versionCode!=code||code<current.versionCode||archive.signatures==null||current.signatures==null||archive.signatures.length==0||current.signatures.length==0)throw new IOException("安装包身份、签名或版本校验失败");
  for(android.content.pm.Signature signature:archive.signatures)if(signature==null)throw new IOException("安装包身份、签名或版本校验失败");
  if(!new HashSet<android.content.pm.Signature>(Arrays.asList(archive.signatures)).equals(new HashSet<android.content.pm.Signature>(Arrays.asList(current.signatures))))throw new IOException("安装包身份、签名或版本校验失败");
 }
 static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.US,"%02x",b&255));return s.toString();}
 static void done(final MainActivity a,final String text){a.runOnUiThread(new Runnable(){public void run(){a.busy=false;a.showOperation(text);}});}
 static String reason(Throwable e){
  if(e instanceof java.net.SocketTimeoutException)return "网络请求超时";
  if(e instanceof java.net.UnknownHostException)return "无法解析服务器域名";
  if(e instanceof java.net.ConnectException)return "无法连接服务器";
  if(e instanceof org.json.JSONException)return "服务器返回的数据格式不正确";
  if(e instanceof SecurityException)return "系统未允许所需的访问权限";
  String message=e.getMessage();if(message!=null){
   if(message.matches("Upload HTTP [0-9]{3}"))return "日志服务器返回 HTTP "+message.substring(message.length()-3);
   if(message.contains("ENOSPC"))return "存储空间不足";
   for(String known:new String[]{"服务器尚未准备有线稳定版","稳定版与实验版安装编号不匹配","外部存储不可用","存储空间不足","版本信息过大","版本信息无效","安装包大小不符","安装包完整性校验失败","安装包身份、签名或版本校验失败","无法替换旧安装包","无法保存安装包","页面已关闭","下载HTTP "})if(message.startsWith(known))return message.substring(0,Math.min(120,message.length()));
   if(message.equals("Invalid receipt"))return "服务器确认信息不完整，日志尚未清理";
   if(message.equals("Report too large")||message.equals("Environment report exceeds local bound"))return "本次诊断数据超出容量限制";
  }
  return e.getClass().getSimpleName();
 }
 static void error(MainActivity a,String stage,Exception e){if(a.closed)return;try{MainActivity.record(a,"update_error",new JSONObject().put("stage",stage).put("type",e.getClass().getSimpleName()).put("reason",reason(e)));}catch(Exception ignored){}done(a,stage+"未完成："+reason(e)+"。可稍后重试，或提交日志。");}
}
