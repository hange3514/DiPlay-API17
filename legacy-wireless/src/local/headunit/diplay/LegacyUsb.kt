package local.headunit.diplay

import android.hardware.usb.*
import android.os.Parcel
import java.io.IOException

data class EndpointDescriptor(val address:Int,val attributes:Int,val packetSize:Int,val interval:Int)
data class InterfaceDescriptor(val id:Int,val alternate:Int,val kind:Int,val subclass:Int,val protocol:Int,val expectedEndpoints:Int,val endpoints:MutableList<EndpointDescriptor> = arrayListOf())
data class ConfigurationDescriptor(val id:Int,val interfaces:MutableList<InterfaceDescriptor> = arrayListOf())

/** Strict, bounded USB configuration parser; configuration boundaries never leak interfaces. */
object DescriptorParser {
 fun parse(bytes:ByteArray):ConfigurationDescriptor {
  fun u(i:Int)=bytes[i].toInt() and 255
  require(bytes.size>=9 && u(0)>=9 && u(1)==2) { "Missing configuration header" }
  val total=u(2) or (u(3) shl 8)
  require(total==bytes.size && total<=65535) { "Truncated configuration" }
  require(u(5)!=0) { "USB configuration zero is reserved for unconfigured state" }
  val config=ConfigurationDescriptor(u(5));var current:InterfaceDescriptor?=null;var at=0
  while(at<total){require(at+2<=total);val size=u(at);val type=u(at+1);require(size>=2 && at+size<=total)
   when(type){
    2 -> require(at==0) { "Nested configuration" }
    4 -> {require(size>=9);val next=InterfaceDescriptor(u(at+2),u(at+3),u(at+5),u(at+6),u(at+7),u(at+4));require(config.interfaces.none { it.id==next.id && it.alternate==next.alternate });config.interfaces.add(next);current=next}
    5 -> {require(size>=7 && current!=null);val e=EndpointDescriptor(u(at+2),u(at+3),u(at+4) or (u(at+5) shl 8),u(at+6));require(e.address and 15!=0 && current.endpoints.none { it.address==e.address });current.endpoints.add(e)}
   };at+=size
  }
  require(config.interfaces.all { it.endpoints.size==it.expectedEndpoints }) { "Endpoint count mismatch" }
  require(config.interfaces.map { it.id }.distinct().size==u(4)) { "Interface count mismatch" }
  return config
 }
}

class LegacyInterface(val descriptor:InterfaceDescriptor) {
 val id get()=descriptor.id
 val alternateSetting get()=descriptor.alternate
 val interfaceClass get()=descriptor.kind
 val interfaceSubclass get()=descriptor.subclass
 val interfaceProtocol get()=descriptor.protocol
 private val endpoints=descriptor.endpoints.map { e ->
  val p=Parcel.obtain();try{p.writeInt(e.address);p.writeInt(e.attributes);p.writeInt(e.packetSize);p.writeInt(e.interval);p.setDataPosition(0);UsbEndpoint.CREATOR.createFromParcel(p)}finally{p.recycle()}
 }
 val endpointCount get()=endpoints.size
 fun getEndpoint(i:Int)=endpoints[i]
 // API17's Parcelable layout has no name/alternate fields; JNI claims by interface ID.
 val platform:UsbInterface by lazy {
  val p=Parcel.obtain();try{p.writeInt(id);p.writeInt(interfaceClass);p.writeInt(interfaceSubclass);p.writeInt(interfaceProtocol);p.writeParcelableArray(endpoints.toTypedArray(),0);p.setDataPosition(0);UsbInterface.CREATOR.createFromParcel(p)}finally{p.recycle()}
 }
}
class LegacyConfiguration(val descriptor:ConfigurationDescriptor) {
 val id get()=descriptor.id
 private val interfaces=descriptor.interfaces.map(::LegacyInterface)
 val interfaceCount get()=interfaces.size
 fun getInterface(i:Int)=interfaces[i]
}
/** Each native transfer is bounded; cancellation must also stop the remaining queries. */
internal object UsbDescriptorReader {
 fun read(transfer:(Int,ByteArray)->Int):List<ConfigurationDescriptor> {
  fun query(value:Int,bytes:ByteArray){
   if(Thread.currentThread().isInterrupted)throw InterruptedException("USB descriptor read cancelled")
   val count=transfer(value,bytes)
   if(Thread.currentThread().isInterrupted)throw InterruptedException("USB descriptor read cancelled")
   check(count==bytes.size){"Incomplete USB descriptor read"}
  }
  val header=ByteArray(18);query(0x100,header)
  val count=header[17].toInt() and 255;require(count in 1..16)
  return (0 until count).map { index ->
   val h=ByteArray(9);query(0x200 or index,h)
   val length=(h[2].toInt() and 255) or ((h[3].toInt() and 255) shl 8);require(length in 9..16384)
   val raw=ByteArray(length);query(0x200 or index,raw)
   DescriptorParser.parse(raw)
  }
 }
}
object LegacyUsb {
 private var deviceName=""
 private var cached:List<LegacyConfiguration> = emptyList()
 fun currentConfiguration(c:UsbDeviceConnection):Int {val b=ByteArray(1);return if(c.controlTransfer(0x80,8,0,0,b,1,1500)==1)b[0].toInt() and 255 else -5}
 fun driver(c:UsbDeviceConnection,id:Int):UsbDriver {val out=ByteArray(256);val rc=NativeUsb.driver(c.fileDescriptor,id,out);return if(rc==0)UsbDriver(String(out,Charsets.US_ASCII).substringBefore('\u0000'))else UsbDriver(error=rc)}
 @Synchronized fun read(device:UsbDevice,connection:UsbDeviceConnection):List<LegacyConfiguration> {
  require(device.vendorId==0x05ac) { "Only the selected Apple device is supported" }
  val result=UsbDescriptorReader.read {value,bytes->connection.controlTransfer(0x80,6,value,0,bytes,bytes.size,1500)}.map(::LegacyConfiguration)
  if(Thread.currentThread().isInterrupted)throw InterruptedException("USB descriptor read cancelled")
  deviceName=device.deviceName;cached=result;return result
 }
 @Synchronized fun configurations(device:UsbDevice):List<LegacyConfiguration>{check(device.deviceName==deviceName) { "Descriptors must be read after USB authorization" };return cached}
 @Synchronized fun clear(){cached=emptyList();deviceName=""}
}
fun UsbDeviceConnection.claimInterface(i:LegacyInterface,force:Boolean):Boolean {
 val before=LegacyUsb.driver(this,i.id);WiredService.usbDiagnostic("claim interface=${i.id} driver=${before.name} driver_errno=${before.error}")
 if(before.userSpace)throw IOException("iPhone USB接口${i.id}被其他应用占用(usbfs)，请关闭手机互联/USB音乐后重试")
 var detached=false
 if(force&&before.error==0&&before.name.isNotEmpty()) {val rc=NativeUsb.bind(fileDescriptor,i.id,false);WiredService.usbDiagnostic("claim_detach interface=${i.id} driver=${before.name} result=$rc");if(rc!=0&&rc!=-61)throw IOException("USB detach errno=${-rc}");detached=rc==0}
 val result=NativeUsb.claim(fileDescriptor,i.id);WiredService.usbDiagnostic("claim interface=${i.id} result=$result")
 if(result!=0){if(detached)NativeUsb.bind(fileDescriptor,i.id,true);throw IOException("USB interface=${i.id} claim errno=${-result} driver=${before.name}")};return true
}
fun UsbDeviceConnection.releaseInterface(i:LegacyInterface)=releaseInterface(i.platform)
fun UsbDeviceConnection.setConfiguration(c:LegacyConfiguration):Boolean {val active=LegacyUsb.currentConfiguration(this);WiredService.usbDiagnostic("check_configuration active=$active expected=${c.id}");if(active!=c.id)throw IOException("USB配置尚未切换完成 active=$active expected=${c.id}");return true}
fun UsbDeviceConnection.setInterface(i:LegacyInterface):Boolean {val result=NativeUsb.alternate(fileDescriptor,i.id,i.alternateSetting);WiredService.usbDiagnostic("set_interface=${i.id} alt=${i.alternateSetting} result=$result");if(result!=0)throw IOException("USB alternate selection errno=${-result}");return true}
