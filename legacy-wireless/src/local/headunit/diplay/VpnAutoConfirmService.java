package local.headunit.diplay;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import org.json.JSONObject;

/** API17 node actions, only for our pending request in the exact system VPN activity. */
public class VpnAutoConfirmService extends AccessibilityService {
 static volatile boolean connected;
 final Handler handler=new Handler();int window=-1,attempts;long lastAction;boolean destroyed;
 final Runnable retry=new Runnable(){public void run(){inspect();}};
 protected void onServiceConnected(){connected=true;log("service_connected");}
 public void onAccessibilityEvent(AccessibilityEvent e){
  if(e==null)return;
  if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
   int next=VpnPromptPolicy.window(str(e.getPackageName()),str(e.getClassName()))?e.getWindowId():-1;
   if(next!=window){window=next;attempts=0;lastAction=0;handler.removeCallbacks(retry);}
  }
  if(window>=0&&e.getWindowId()==window)inspect();
 }
 public void onInterrupt(){window=-1;handler.removeCallbacks(retry);}
 public void onDestroy(){destroyed=true;connected=false;handler.removeCallbacks(retry);super.onDestroy();}
 static String str(CharSequence s){return s==null?null:s.toString();}
 boolean pending(){MainActivity a=MainActivity.active;return a!=null&&!a.closed&&a.permissionPending;}
 void inspect(){
  if(destroyed||window<0||attempts>=6||!pending())return;
  if(SystemClock.uptimeMillis()-lastAction<200){handler.removeCallbacks(retry);handler.postDelayed(retry,220);return;}
  AccessibilityNodeInfo root;try{root=getRootInActiveWindow();}catch(RuntimeException e){return;}if(root==null)return;
  ArrayList<AccessibilityNodeInfo> nodes=new ArrayList<AccessibilityNodeInfo>();
  try{
   if(root.getWindowId()!=window||!"com.android.vpndialogs".equals(str(root.getPackageName())))return;
   collect(root,nodes,0);if(nodes.size()>=80)return;StringBuilder text=new StringBuilder();
   for(AccessibilityNodeInfo n:nodes)if(n.getText()!=null)text.append(n.getText()).append(' ');
   String label=getApplicationInfo().loadLabel(getPackageManager()).toString();
   if(!VpnPromptPolicy.target(text.toString(),label,pending()))return;
   AccessibilityNodeInfo check=null,button=null;int checks=0,buttons=0;
   for(AccessibilityNodeInfo n:nodes){
    if(n.isCheckable()&&n.isEnabled()&&n.isClickable()){checks++;check=n;}
    if("android.widget.Button".equals(str(n.getClassName()))&&n.isEnabled()&&n.isClickable()&&VpnPromptPolicy.confirm(str(n.getText()))){buttons++;button=n;}
   }
   // API17 has no view-id lookup. Require unique standard controls; never guess coordinates.
   if(checks>1||buttons>1)return;
   boolean ticking=check!=null&&!check.isChecked();AccessibilityNodeInfo action=ticking?check:button;
   if(action==null)return;
   attempts++;lastAction=SystemClock.uptimeMillis();boolean ok=action.performAction(AccessibilityNodeInfo.ACTION_CLICK);
   log((ticking?"trust_checkbox":"confirm_button")+" actionAccepted="+ok);
   if(ok&&!ticking){window=-1;handler.removeCallbacks(retry);}
   else{handler.removeCallbacks(retry);handler.postDelayed(retry,250);}
  }catch(RuntimeException e){log("node_unavailable="+e.getClass().getSimpleName());}
  finally{for(AccessibilityNodeInfo n:nodes)if(n!=root)n.recycle();root.recycle();}
 }
 void collect(AccessibilityNodeInfo node,ArrayList<AccessibilityNodeInfo> nodes,int depth){
  nodes.add(node);if(depth>=10)return;
  for(int i=0;i<node.getChildCount()&&nodes.size()<80;i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)collect(child,nodes,depth+1);}
 }
 void log(String message){try{MainActivity.record(this,"VPN_AUTO_CONFIRM",new JSONObject().put("detail",message));}catch(Exception ignored){}}
}
