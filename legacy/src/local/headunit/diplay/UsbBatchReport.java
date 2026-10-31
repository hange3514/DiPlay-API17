package local.headunit.diplay;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import org.json.*;

/** One retained report, updated atomically between trials; acknowledged by id+revision. */
final class UsbBatchReport {
 static File path(Context c){return new File(c.getFilesDir(),"usb-batch.json");}
 static synchronized void save(Context c,JSONObject report)throws Exception{
  byte[] bytes=report.toString().getBytes("UTF-8");if(bytes.length>100000)throw new IOException("Batch report too large");
  AtomicFile f=new AtomicFile(path(c));FileOutputStream out=f.startWrite();
  try{out.write(bytes);f.finishWrite(out);}catch(Exception e){f.failWrite(out);throw e;}
 }
 static synchronized void expire(Context c){File f=path(c);if(f.exists()&&System.currentTimeMillis()-f.lastModified()>86400000L)new AtomicFile(f).delete();}
 static synchronized JSONObject read(Context c)throws Exception{
  AtomicFile f=new AtomicFile(path(c));if(!path(c).exists()&&!new File(path(c)+".bak").exists())return null;
  InputStream in=f.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream();
  try{byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(out.size()+n>100000)throw new IOException("Batch report bound");out.write(b,0,n);}}finally{in.close();}
  JSONObject result=new JSONObject(new String(out.toByteArray(),"UTF-8"));
  if(System.currentTimeMillis()-result.getLong("time")>86400000L){f.delete();return null;}return result;
 }
 static String token(JSONObject j){return j.optString("batchId")+":"+j.optInt("revision");}
 static String append(Context c)throws Exception{
  JSONObject j=read(c);if(j==null)return "";if("running".equals(j.optString("state")))j.put("partialOrInterrupted",true);
  // An interrupted activity/process never resumes active experiments on boot.
  MainActivity.record(c,"USB_BATCH_REPORT",j);return token(j);
 }
 static synchronized void acknowledge(Context c,String token)throws Exception{
  if(token.length()==0)return;JSONObject j=read(c);if(j!=null&&token.equals(token(j)))new AtomicFile(path(c)).delete();
 }
}
