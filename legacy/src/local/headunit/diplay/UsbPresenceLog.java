package local.headunit.diplay;
import android.content.Context;
import android.hardware.usb.*;
import java.io.*;
import java.util.Locale;
import org.json.*;

/** Read-only failure evidence. Never opens USB, requests permission, or resets a port. */
final class UsbPresenceLog {
 static void recordTransition(Context c,String transition,UsbDevice d){
  if(d==null||d.getVendorId()!=0x05ac)return;
  try{UsbManager manager=(UsbManager)c.getSystemService(Context.USB_SERVICE);int appleCount=appleCount(manager);String speed="",configuration="",usbVersion="";File[] nodes=new File("/sys/bus/usb/devices").listFiles();boolean readable=nodes!=null;
   if(nodes!=null)for(File node:nodes){String vid=read(new File(node,"idVendor")),pid=read(new File(node,"idProduct"));if(!String.format(Locale.US,"%04x",d.getVendorId()).equalsIgnoreCase(vid)||!String.format(Locale.US,"%04x",d.getProductId()).equalsIgnoreCase(pid))continue;speed=read(new File(node,"speed"));configuration=read(new File(node,"bConfigurationValue"));usbVersion=read(new File(node,"version"));break;}
   StringBuilder interfaces=new StringBuilder();for(int n=0;n<Math.min(d.getInterfaceCount(),12);n++){UsbInterface intf=d.getInterface(n);if(interfaces.length()>0)interfaces.append(';');interfaces.append(intf.getId()).append(':').append(intf.getInterfaceClass()).append('.').append(intf.getInterfaceSubclass()).append('.').append(intf.getInterfaceProtocol()).append('/').append(intf.getEndpointCount());for(int k=0;k<Math.min(intf.getEndpointCount(),8);k++){UsbEndpoint ep=intf.getEndpoint(k);interfaces.append(',').append(ep.getDirection()).append('.').append(ep.getType()).append('.').append(ep.getMaxPacketSize());}}
   JSONObject row=new JSONObject().put("usbTransition",transition).put("deviceVid",d.getVendorId()).put("devicePid",d.getProductId()).put("deviceClass",d.getDeviceClass()).put("deviceSubclass",d.getDeviceSubclass()).put("deviceProtocol",d.getDeviceProtocol()).put("usbInterfaceCount",d.getInterfaceCount()).put("devicePermission",manager!=null&&manager.hasPermission(d)).put("appleDevicesPresent",appleCount).put("usbSysfsReadable",readable).put("usbInterfaceSummary",interfaces.toString().substring(0,Math.min(interfaces.length(),180)));
   int speedValue=parseInt(speed);int configValue=parseInt(configuration);if(speedValue>=0)row.put("usbSpeedMbps",speedValue);if(configValue>=0)row.put("usbConfiguration",configValue);if(usbVersion.matches("[0-9.]{1,8}"))row.put("usbVersion",usbVersion);
   MainActivity.record(c,"USB_DEVICE",row);
  }catch(Exception e){try{MainActivity.record(c,"USB_DEVICE",new JSONObject().put("usbTransition",transition).put("deviceVid",d.getVendorId()).put("devicePid",d.getProductId()).put("usbError",e.getClass().getSimpleName()));}catch(Exception ignored){}}
 }
 static void recordCurrentPhones(Context c,String transition){try{UsbManager manager=(UsbManager)c.getSystemService(Context.USB_SERVICE);int count=0;for(UsbDevice d:manager.getDeviceList().values())if(d.getVendorId()==0x05ac){count++;recordTransition(c,transition,d);}if(count==0)MainActivity.record(c,"USB_DEVICE",new JSONObject().put("usbTransition",transition).put("appleDevicesPresent",0));}catch(Exception ignored){}}
 private static int appleCount(UsbManager manager){if(manager==null)return 0;int count=0;for(UsbDevice item:manager.getDeviceList().values())if(item.getVendorId()==0x05ac)count++;return count;}
 private static int parseInt(String value){try{return Integer.parseInt(value.trim());}catch(Exception e){return -1;}}
 static void capture(Context c,String reason){try{
  UsbManager manager=(UsbManager)c.getSystemService(Context.USB_SERVICE);JSONArray framework=new JSONArray(),kernel=new JSONArray();
  for(UsbDevice d:manager.getDeviceList().values()){if(framework.length()>=32)break;framework.put(new JSONObject().put("vid",d.getVendorId()).put("pid",d.getProductId()).put("interfaces",d.getInterfaceCount()).put("permission",manager.hasPermission(d)));}
  File[] nodes=new File("/sys/bus/usb/devices").listFiles();int visited=0;
  if(nodes!=null)for(File node:nodes){if(++visited>128||kernel.length()>=32)break;String vid=read(new File(node,"idVendor"));if(vid.matches("[a-fA-F0-9]{4}"))kernel.put(new JSONObject().put("vid",vid).put("pid",read(new File(node,"idProduct"))).put("configuration",read(new File(node,"bConfigurationValue"))).put("speedMbps",read(new File(node,"speed"))).put("usbVersion",read(new File(node,"version"))));}
  MainActivity.record(c,"USB_PRESENCE",new JSONObject().put("reason",reason).put("framework",framework).put("sysfsReadable",nodes!=null).put("kernel",kernel));
 }catch(Exception e){try{MainActivity.record(c,"USB_PRESENCE",new JSONObject().put("error",e.getClass().getSimpleName()));}catch(Exception ignored){}}}
 static String read(File f){try{FileInputStream in=new FileInputStream(f);try{byte[] b=new byte[32];int n=in.read(b);return n>0?new String(b,0,n,"US-ASCII").trim():"";}finally{in.close();}}catch(Exception e){return "unreadable";}}
}
