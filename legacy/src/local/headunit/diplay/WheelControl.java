package local.headunit.diplay;
import android.content.*;import android.os.*;import java.io.Closeable;import java.util.concurrent.*;import org.json.JSONObject;
/** Connection-owned passive OEM receiver, bounded dispatch to the same phone session. */
public final class WheelControl extends BroadcastReceiver implements Closeable {
 public interface Sink {Object session();boolean send(Object session,int action);}
 private final Context context;private final Sink sink;private final WheelKeyPolicy policy=new WheelKeyPolicy();
 private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(4));
 private volatile boolean closed;private boolean registered;
 private static boolean supported(Context c){try{return Build.VERSION.SDK_INT==17&&"MX9A0".equals(Build.MODEL)&&c.getPackageManager().getPackageInfo("com.byd.multimediaservice",0).versionCode==382;}catch(Exception e){return false;}}
 public WheelControl(Context c,Sink s){this(c,s,supported(c));}
 WheelControl(Context c,Sink s,boolean supported){context=c.getApplicationContext();sink=s;try{if(supported){context.registerReceiver(this,new IntentFilter("APP_REV_DATA"));registered=true;log(0,"receiver_registered");}}catch(Exception e){log(0,"receiver_unavailable");}}
 public boolean duplicateAndroid(int key){return !closed&&registered&&!MainActivity.wheelProbeActive()&&sink.session()!=null&&policy.duplicateAndroid(key,SystemClock.elapsedRealtime());}
 /** Android key-event path; independent of the OEM broadcast receiver registration. */
 public boolean acceptAndroidKey(int key){return !closed&&policy.acceptAndroid(WheelKeyPolicy.androidAction(key),SystemClock.elapsedRealtime());}
 public void onReceive(Context c,Intent i){if(closed||MainActivity.wheelProbeActive()||!"APP_REV_DATA".equals(i.getAction()))return;try{final byte[] raw=i.getByteArrayExtra("list");final int length=raw==null?-1:raw.length;final int h0=raw!=null&&raw.length>0?raw[0]&255:-1;final int h1=raw!=null&&raw.length>1?raw[1]&255:-1;final int keyCode=raw!=null&&raw.length==3&&h0==4&&h1==1?raw[2]&255:-1;final int action=WheelKeyPolicy.decode(raw);if(action==0){log(0,keyCode>=0?"unmapped_oem_key":"unrecognized_oem_frame",keyCode,length,h0,h1);return;}final Object session=sink.session();if(session==null){log(action,"no_active_session",keyCode,length,h0,h1);return;}final long received=SystemClock.elapsedRealtime();if(!policy.accept(action,received)){log(action,"duplicate_ignored",keyCode,length,h0,h1);return;}try{worker.execute(new Runnable(){public void run(){if(closed)return;if(MainActivity.wheelProbeActive()||sink.session()!=session||SystemClock.elapsedRealtime()-received>600){log(action,"stale_ignored",keyCode,length,h0,h1);return;}try{log(action,sink.send(session,action)?"reports_written_not_phone_acknowledged":"report_write_failed",keyCode,length,h0,h1);}catch(Exception e){log(action,"send_failed_"+e.getClass().getSimpleName(),keyCode,length,h0,h1);}}});}catch(RejectedExecutionException e){log(action,"queue_full",keyCode,length,h0,h1);}}catch(Exception e){log(0,"invalid_event");}}
 private void log(int action,String result){log(action,result,-1,-1,-1,-1);}
 private void log(int action,String result,int keyCode){log(action,result,keyCode,-1,-1,-1);}
 private void log(int action,String result,int keyCode,int frameLength,int header0,int header1){try{JSONObject row=new JSONObject().put("wheelAction",action).put("keyPath","oem_broadcast").put("result",result).put("connection",WiredService.currentConnectionId());if(keyCode>=0)row.put("keyCode",keyCode);if(frameLength>=0)row.put("oemFrameLength",frameLength);if(header0>=0)row.put("oemHeader0",header0);if(header1>=0)row.put("oemHeader1",header1);MainActivity.record(context,"WHEEL_CONTROL",row);}catch(Exception ignored){}}
 public void close(){if(closed)return;closed=true;if(registered){registered=false;try{context.unregisterReceiver(this);}catch(Exception ignored){}}worker.shutdownNow();}
}
