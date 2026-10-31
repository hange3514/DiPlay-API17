package local.headunit.diplay;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Immutable serialized entries; protocol threads never wait for fsync or rescan JSON text. */
final class DiagnosticLog {
 private static final class Entry {
  final String id,line;final long time;final byte[] encoded;final boolean routine;
  Entry(JSONObject r)throws Exception{id=r.getString("id");time=r.getLong("time");line=r.toString();encoded=(line+"\n").getBytes("UTF-8");routine=DiagnosticPolicy.routine(r.optString("event"));}
 }
 private static final ArrayList<Entry> rows=new ArrayList<Entry>();
 private static final ScheduledExecutorService disk=Executors.newSingleThreadScheduledExecutor();
 private static Context context;private static boolean loaded,queued;private static int bytes;
 private static void load(Context c)throws Exception {
  if(loaded)return;context=c.getApplicationContext();File f=new File(context.getFilesDir(),"events.jsonl");
  if(f.exists()||new File(f.getPath()+".bak").exists()){
   BufferedReader in=new BufferedReader(new InputStreamReader(new AtomicFile(f).openRead(),"UTF-8"));try{String line;while((line=in.readLine())!=null){try{Entry r=new Entry(new JSONObject(line));rows.add(r);bytes+=r.encoded.length;trim();}catch(JSONException ignored){}}}finally{in.close();}
  }loaded=true;trim();expireExport();disk.scheduleAtFixedRate(new Runnable(){public void run(){try{synchronized(DiagnosticLog.class){trim();}expireExport();persistSnapshot();}catch(Exception e){android.util.Log.e("DiPlayLog","Retention cleanup failed",e);}}},1,1,TimeUnit.HOURS);
 }
 private static void expireExport(){if(context!=null)UsbBatchReport.expire(context);ReportIo.expireBackup(new File(android.os.Environment.getExternalStorageDirectory(),"DiPlayCompat"),System.currentTimeMillis());}
 private static void trim(){long cutoff=System.currentTimeMillis()-86400000L;for(Iterator<Entry> it=rows.iterator();it.hasNext();){Entry r=it.next();if(r.time<cutoff){bytes-=r.encoded.length;it.remove();}}while(rows.size()>400||bytes>150000){int drop=0;for(int n=0;n<rows.size();n++)if(rows.get(n).routine){drop=n;break;}bytes-=rows.remove(drop).encoded.length;}}
 static synchronized void record(Context c,String name,JSONObject detail)throws Exception {load(c);Entry r=new Entry(new JSONObject().put("id",UUID.randomUUID().toString()).put("time",System.currentTimeMillis()).put("event",name+" "+detail.toString()));rows.add(r);bytes+=r.encoded.length;trim();schedule();}
 private static void schedule(){if(queued)return;queued=true;disk.schedule(new Runnable(){public void run(){synchronized(DiagnosticLog.class){queued=false;}try{persistSnapshot();}catch(Exception e){android.util.Log.e("DiPlayLog","Log persistence failed",e);}}},300,TimeUnit.MILLISECONDS);}
 static JSONArray snapshot(Context c)throws Exception {List<Entry> copy;synchronized(DiagnosticLog.class){load(c);trim();copy=new ArrayList<Entry>(rows);}JSONArray result=new JSONArray();for(Entry r:copy)result.put(new JSONObject(r.line));return result;}
 static void acknowledge(Context c,JSONArray sent)throws Exception {
  HashSet<String> ids=new HashSet<String>();for(int i=0;i<sent.length();i++)ids.add(sent.getJSONObject(i).getString("id"));
  Future<?> durable;
  synchronized(DiagnosticLog.class){load(c);for(Iterator<Entry> it=rows.iterator();it.hasNext();){Entry r=it.next();if(ids.contains(r.id)){bytes-=r.encoded.length;it.remove();}}
   // Same writer serializes every snapshot, so an older delayed flush cannot resurrect acknowledged rows.
   durable=disk.submit(new Callable<Void>(){public Void call()throws Exception{persistSnapshot();return null;}});
  }
  durable.get();
 }
 private static void persistSnapshot()throws Exception {
  List<Entry> copy;Context c;synchronized(DiagnosticLog.class){copy=new ArrayList<Entry>(rows);c=context;}
  AtomicFile f=new AtomicFile(new File(c.getFilesDir(),"events.jsonl"));FileOutputStream out=f.startWrite();try{for(Entry r:copy)out.write(r.encoded);f.finishWrite(out);}catch(Exception e){f.failWrite(out);throw e;}
 }
}
