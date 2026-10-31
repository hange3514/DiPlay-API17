package local.headunit.diplay;
import java.util.ArrayList;
import org.json.*;

/** Bounded memory-only capture. Never write disk or hold USB resources here. */
final class UsbBatchCapture {
 private static int bytes;private static String connection="";private static final ArrayList<String> rows=new ArrayList<String>();
 static synchronized void begin(String id){connection=id;rows.clear();bytes=0;}
 static synchronized void accept(String name,JSONObject detail){
  if(connection.length()==0||!connection.equals(detail.optString("connection")))return;
  if(!(name.equals("NCM")||name.equals("CONNECT_SUMMARY")||name.equals("FAILED")||name.equals("USB_NETWORK")||name.equals("CONNECT_TIMING")))return;
  // Packet traces are already in the normal log. Keep early interface evidence
  // in the durable batch instead of evicting it for repeated TCP/ICMP traces.
  if(name.equals("USB_NETWORK"))return; // Retained in ordinary logs; batch prioritizes IN timings.
  if(name.equals("NCM")&&detail.optString("detail").startsWith("ncm status notification"))return;
  if(name.equals("CONNECT_TIMING"))return; // Summary contains every phase duration.
  String line=name+" "+detail.toString();if(line.length()>1600)return;
  try{int size=line.getBytes("UTF-8").length;
   while(!rows.isEmpty()&&(rows.size()>=60||bytes+size>3500))bytes-=rows.remove(0).getBytes("UTF-8").length;
   rows.add(line);bytes+=size;
  }catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);}
 }
 static synchronized JSONArray finish(){JSONArray out=new JSONArray();for(String row:rows)out.put(row);rows.clear();bytes=0;connection="";return out;}
}
