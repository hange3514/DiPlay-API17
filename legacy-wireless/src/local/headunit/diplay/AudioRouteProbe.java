package local.headunit.diplay;
import android.media.AudioManager;
import org.json.JSONObject;
/** API17 deprecated route masks. They describe policy configuration, not acoustic loopback. */
public final class AudioRouteProbe {
 private AudioRouteProbe(){}
 public static JSONObject snapshot(AudioManager manager){JSONObject out=new JSONObject();try{out.put("scope","api17_deprecated_routing_policy_mask_not_hardware_loopback").put("currentMode",manager.getMode());add(out,"current",manager,manager.getMode());add(out,"normal",manager,AudioManager.MODE_NORMAL);add(out,"ringtone",manager,AudioManager.MODE_RINGTONE);add(out,"inCall",manager,AudioManager.MODE_IN_CALL);add(out,"communication",manager,AudioManager.MODE_IN_COMMUNICATION);}catch(Throwable e){try{out.put("status","partial").put("error",e.getClass().getSimpleName());}catch(Exception ignored){}}return out;}
 private static void add(JSONObject out,String label,AudioManager manager,int mode){try{int mask=manager.getRouting(mode);out.put(label+"Mask",mask).put(label+"Routes",routeNames(mask)).put(label+"Status",mask<0?"sentinel_or_unknown":"measured_policy_mask");}catch(Throwable e){try{out.put(label+"Status","unavailable").put(label+"Error",e.getClass().getSimpleName());}catch(Exception ignored){}}}
 public static String routeNames(int mask){if(mask<0)return "unreported_sentinel";StringBuilder b=new StringBuilder();append(b,mask,AudioManager.ROUTE_EARPIECE,"earpiece");append(b,mask,AudioManager.ROUTE_SPEAKER,"speaker");append(b,mask,AudioManager.ROUTE_BLUETOOTH,"bluetooth");append(b,mask,AudioManager.ROUTE_BLUETOOTH_SCO,"bluetooth_sco");append(b,mask,AudioManager.ROUTE_HEADSET,"headset");append(b,mask,AudioManager.ROUTE_BLUETOOTH_A2DP,"bluetooth_a2dp");return b.length()==0?"none_or_unknown":b.toString();}
 private static void append(StringBuilder b,int mask,int bit,String name){if((mask&bit)!=0){if(b.length()>0)b.append(',');b.append(name);}}
}
