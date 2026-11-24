package local.headunit.diplay;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Immutable serialized entries; protocol threads never wait for fsync or rescan JSON text. */
final class DiagnosticLog {
 private static final int MAX_ROWS=12000,MAX_BYTES=8*1024*1024;
 private static final long RETENTION_MS=86400000L,EXPIRY_SWEEP_INTERVAL_MS=60000L;
 static final class Entry {
  final String id,type;
  final long time;
  final int encodedLength;
  private final byte[] encoded;
  private final boolean routine;

  Entry(JSONObject row)throws Exception {this(row,row.toString());}

  private Entry(JSONObject row,String line)throws Exception {
   id=row.getString("id");
   time=row.getLong("time");
   String event=row.optString("event");
   int detailStart=event.indexOf('{');
   String name=(detailStart<0?event:event.substring(0,detailStart)).trim();
   type=name.length()<=64?name:"";
   encoded=(line+"\n").getBytes("UTF-8");
   encodedLength=encoded.length;
   routine=DiagnosticPolicy.routine(event);
  }

  JSONObject json()throws Exception {
   return new JSONObject(new String(encoded,0,encodedLength-1,"UTF-8"));
  }

  JSONObject detail()throws Exception {
   String event=json().optString("event");
   int start=event.indexOf('{');
   if(start<0)return null;
   try{return new JSONObject(event.substring(start));}catch(JSONException ignored){return null;}
  }
 }
 private static LinkedHashSet<Entry> rows=new LinkedHashSet<Entry>();
 private static LinkedHashSet<Entry> routineRows=new LinkedHashSet<Entry>();
 private static final ScheduledExecutorService disk=Executors.newSingleThreadScheduledExecutor();
 private static Context context;private static boolean loaded;private static int bytes;
 private static long nextExpirySweepNs=System.nanoTime(),contentRevision;
 private static long persistedRevision=-1; // Only accessed under persistenceLock.
 private static ScheduledFuture<?> pendingFlush;
 private static long flushDueNs,flushGeneration;
 private static Future<Void> loading;
 private static long historyRetryAfterNanos;

 static void prepare(Context c){startLoad(c,false);}

 private static synchronized Future<Void> startLoad(Context c,boolean retry){
  if(loaded)return null;
  if(loading!=null&&(!loading.isDone()||(!retry&&historyRetryAfterNanos-System.nanoTime()>0)))return loading;
  final Context app=c.getApplicationContext();
  loading=disk.submit(new Callable<Void>(){
   public Void call()throws Exception{
    try{restoreHistory(app);return null;}
    catch(Exception error){
     synchronized(DiagnosticLog.class){
      historyRetryAfterNanos=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
     }
     android.util.Log.e("DiPlayLog","Log history read failed",error);
     throw error;
    }
   }
  });
  return loading;
 }

 private static void ensureLoaded(Context c)throws Exception{
  Future<Void> task=startLoad(c,true);
  if(task==null)return;
  try{task.get();}
  catch(ExecutionException failure){
   Throwable cause=failure.getCause();
   if(cause instanceof Exception)throw (Exception)cause;
   if(cause instanceof Error)throw (Error)cause;
   throw failure;
  }
 }

 private static void restoreHistory(Context app)throws Exception{
  File file=new File(app.getFilesDir(),"events.jsonl");
  LinkedHashSet<Entry> restored=new LinkedHashSet<Entry>();
  LinkedHashSet<Entry> restoredRoutine=new LinkedHashSet<Entry>();
  int restoredBytes=0;
  boolean trimmedHistory=false;
  long cutoff=System.currentTimeMillis()-RETENTION_MS;
  if(file.exists()||new File(file.getPath()+".bak").exists()){
   BufferedReader in=new BufferedReader(new InputStreamReader(new AtomicFile(file).openRead(),"UTF-8"));
   try{
    String line;
    while((line=in.readLine())!=null){
     try{
      JSONObject row=new JSONObject(line);
      if(row.getLong("time")<cutoff){trimmedHistory=true;continue;}
      Entry entry=new Entry(row,line);
      restored.add(entry);
      if(entry.routine)restoredRoutine.add(entry);
      restoredBytes+=entry.encodedLength;
      int boundedBytes=trimToCapacity(restored,restoredRoutine,restoredBytes);
      if(boundedBytes!=restoredBytes)trimmedHistory=true;
      restoredBytes=boundedBytes;
     }catch(JSONException ignored){trimmedHistory=true;}
    }
   }finally{in.close();}
  }
  synchronized(DiagnosticLog.class){
   // Fresh entries are already buffered. Never persist them over unread history.
   boolean recordedWhileLoading=!rows.isEmpty();
   restored.addAll(rows);
   restoredRoutine.addAll(routineRows);
   rows=restored;
   routineRows=restoredRoutine;
   bytes+=restoredBytes;
   context=app;
   loaded=true;
   boolean trimmed=trim(true);
   if(trimmedHistory)contentRevision++;
   if(recordedWhileLoading||trimmedHistory||trimmed)schedule(false);
  }
  disk.execute(new Runnable(){public void run(){try{expireExport();}catch(Exception e){android.util.Log.e("DiPlayLog","Export retention cleanup failed",e);}}});
  disk.scheduleAtFixedRate(new Runnable(){public void run(){try{boolean changed;synchronized(DiagnosticLog.class){changed=trim(true);}expireExport();if(changed)persistSnapshot();}catch(Exception e){android.util.Log.e("DiPlayLog","Retention cleanup failed",e);}}},15,15,TimeUnit.MINUTES);
 }
 private static void expireExport(){if(context!=null)UsbBatchReport.expire(context);ReportIo.expireBackup(new File(android.os.Environment.getExternalStorageDirectory(),"DiPlayCompat"),System.currentTimeMillis());}
 private static boolean trim(){return trim(false);}
 private static boolean trim(boolean forceExpirySweep){
  boolean changed=false;
  long nowNs=System.nanoTime();
  if(forceExpirySweep||nowNs-nextExpirySweepNs>=0){
   long cutoff=System.currentTimeMillis()-RETENTION_MS;
   for(Iterator<Entry> it=rows.iterator();it.hasNext();){
    Entry row=it.next();
    if(row.time>=cutoff)continue;
    it.remove();
    removeAccounting(row);
    changed=true;
   }
   nextExpirySweepNs=nowNs+TimeUnit.MILLISECONDS.toNanos(EXPIRY_SWEEP_INTERVAL_MS);
  }
  int boundedBytes=trimToCapacity(rows,routineRows,bytes);
  if(boundedBytes!=bytes)changed=true;
  bytes=boundedBytes;
  if(changed)contentRevision++;
  return changed;
 }
 private static int trimToCapacity(LinkedHashSet<Entry> entries,LinkedHashSet<Entry> routine,int usedBytes){
  while(entries.size()>MAX_ROWS||usedBytes>MAX_BYTES){
   Entry oldest=(routine.isEmpty()?entries:routine).iterator().next();
   entries.remove(oldest);
   routine.remove(oldest);
   usedBytes-=oldest.encodedLength;
  }
  return usedBytes;
 }
 private static void removeAccounting(Entry row){
  bytes-=row.encodedLength;
  if(row.routine)routineRows.remove(row);
 }
 static synchronized void record(Context c,String name,JSONObject detail)throws Exception{
  startLoad(c,false);
  append(UUID.randomUUID().toString(), name, detail);
 }
 private static void append(String id,String name,JSONObject detail)throws Exception{
  Entry entry=new Entry(new JSONObject().put("id",id).put("time",System.currentTimeMillis()).put("event",name+" "+detail.toString()));
  rows.add(entry);
  if(entry.routine)routineRows.add(entry);
  bytes+=entry.encodedLength;contentRevision++;trim();
  if(loaded)schedule(entry.routine);
 }
 static void recordImmediately(Context c,String name,JSONObject detail)throws Exception {record(c,name,detail);ensureLoaded(c);persistSnapshot();}
 /** A recovery interrupted after fsync may replay the same archived incident. */
 static void recordRecovery(Context c,String incidentId,JSONObject detail)throws Exception{
  ensureLoaded(c);
  synchronized(DiagnosticLog.class){
   boolean present=false;
   for(Entry entry:rows)if(incidentId.equals(entry.id)){present=true;break;}
   if(!present)append(incidentId,"PROCESS_RECOVERY",detail);
  }
  persistSnapshot();
 }
 /** Routine playback metrics can share one on-disk snapshot. Milestones still flush promptly. */
 private static void schedule(boolean routine){
  long delayMs=routine?8000L:300L;
  long due=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(delayMs);
  if(pendingFlush!=null&&due-flushDueNs>=0)return;
  if(pendingFlush!=null)pendingFlush.cancel(false);
  flushDueNs=due;
  final long generation=++flushGeneration;
  pendingFlush=disk.schedule(new Runnable(){public void run(){
   synchronized(DiagnosticLog.class){if(generation!=flushGeneration)return;pendingFlush=null;}
   try{persistSnapshot();}catch(Exception e){android.util.Log.e("DiPlayLog","Log persistence failed",e);}
  }},delayMs,TimeUnit.MILLISECONDS);
 }
 /** Copy at most MAX_ROWS references and let report preparation decode selected rows. */
 static List<Entry> reportRows(Context c)throws Exception {
  ensureLoaded(c);
  synchronized(DiagnosticLog.class){
   if(trim(true))schedule(false);
   return new ArrayList<Entry>(rows);
  }
 }
 static JSONArray snapshot(Context c)throws Exception {
  JSONArray result=new JSONArray();
  for(Entry row:reportRows(c))result.put(row.json());
  return result;
 }
 static int count(Context c)throws Exception {
  ensureLoaded(c);
  synchronized(DiagnosticLog.class){
   if(trim(true))schedule(false);
   return rows.size();
  }
 }
 static void acknowledge(Context c,JSONArray sent)throws Exception {
  HashSet<String> ids=new HashSet<String>();for(int i=0;i<sent.length();i++)ids.add(sent.getJSONObject(i).getString("id"));
  ensureLoaded(c);
  Future<?> durable;
  synchronized(DiagnosticLog.class){
   int previous=rows.size();
   for(Iterator<Entry> it=rows.iterator();it.hasNext();){Entry r=it.next();if(ids.contains(r.id)){it.remove();removeAccounting(r);}}
   if(rows.size()!=previous)contentRevision++;
   // Same writer serializes every snapshot, so an older delayed flush cannot resurrect acknowledged rows.
   durable=disk.submit(new Callable<Void>(){public Void call()throws Exception{persistSnapshot();return null;}});
  }
  durable.get();
 }
 static int clear(Context c)throws Exception {
  ensureLoaded(c);
  Future<?> durable;final int removed;
  synchronized(DiagnosticLog.class){removed=rows.size();rows.clear();routineRows.clear();bytes=0;
   if(removed!=0)contentRevision++;
   durable=disk.submit(new Callable<Void>(){public Void call()throws Exception{persistSnapshot();return null;}});
  }
  durable.get();return removed;
 }
 private static final Object persistenceLock=new Object();
 private static void persistSnapshot()throws Exception {
  synchronized(persistenceLock){
   List<Entry> copy;
   Context c;
   long revision;
   synchronized(DiagnosticLog.class){
    revision=contentRevision;
    if(revision==persistedRevision)return;
    copy=new ArrayList<Entry>(rows);
    c=context;
   }
   File file=new File(c.getFilesDir(),"events.jsonl");
   AtomicFile f=new AtomicFile(file);
   FileOutputStream out=f.startWrite();
   try{
    BufferedOutputStream buffered=new BufferedOutputStream(out,64*1024);
    for(Entry r:copy)buffered.write(r.encoded);
    buffered.flush();
    f.finishWrite(out);
    if(file.isFile()&&!new File(file.getPath()+".bak").exists())persistedRevision=revision;
   }catch(Exception e){f.failWrite(out);throw e;}
  }
 }
}
