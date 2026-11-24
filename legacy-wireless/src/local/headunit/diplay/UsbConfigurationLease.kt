package local.headunit.diplay

import android.hardware.usb.UsbDeviceConnection
import java.io.Closeable
import java.io.IOException

data class UsbDriver(val name:String="",val error:Int=0) {
 val absent get()=error==-61 // ENODATA, the interface has no driver.
 val userSpace get()=error==0&&name=="usbfs"
}
interface UsbConfigurationOps {
 fun current():Int
 fun configure(id:Int):Int
 fun interfaces(config:Int):List<Int>
 fun driver(id:Int):UsbDriver
 fun bind(id:Int,connect:Boolean):Int
 fun log(message:String)
}

/** Detaches only this phone's kernel bindings; never steals another process's usbfs claim. */
class UsbConfigurationTakeover(private val ops:UsbConfigurationOps,private val target:Int):Closeable {
 init { require(target in 1..255) { "Invalid USB configuration target" } }
 private var original:Int?=null
 private val detached=ArrayList<Int>()
 private var originalBindings:Map<Int,UsbDriver> = emptyMap()
 private var changed=false
 private var closed=false
 private fun checkOpen(){if(closed||Thread.currentThread().isInterrupted)throw InterruptedException("USB setup cancelled")}
 private fun inspect(config:Int):Map<Int,UsbDriver> = ops.interfaces(config).associateWith {id->ops.driver(id).also{ops.log("configuration=$config interface=$id driver=${it.name.ifEmpty{"none"}} errno=${it.error}")}}
 private fun rejectUserSpace(drivers:Map<Int,UsbDriver>){
  val owner=drivers.entries.firstOrNull{it.value.userSpace}
  if(owner!=null)throw IOException("iPhone USB接口${owner.key}被其他应用占用(usbfs)，请关闭车机的手机互联/USB音乐功能后重试")
  val bad=drivers.entries.firstOrNull{it.value.error!=0&&!it.value.absent}
  if(bad!=null)throw IOException("读取USB接口${bad.key}占用信息失败 errno=${-bad.value.error}")
 }
 @Synchronized fun prepare(){
  checkOpen();check(original==null);val active=ops.current();require(active in 1..255){"Cannot read active USB configuration ($active)"};original=active
  val initial=inspect(active);originalBindings=initial
  if(active==target){ops.log("configuration_already_active=$target skip_reset");return}
  // Preflight all interfaces before changing any binding.
  rejectUserSpace(initial);checkOpen()
  var result=ops.configure(target);ops.log("configuration_switch=$active->$target result=$result")
  if(result==-16){
   val occupied=inspect(active);rejectUserSpace(occupied)
   for((id,driver) in occupied){checkOpen();if(driver.absent||driver.name.isEmpty())continue
    val now=ops.driver(id);if(now.absent)continue;if(now.userSpace||now.error!=0||now.name!=driver.name)throw IOException("USB driver changed during takeover interface=$id")
    val rc=ops.bind(id,false);ops.log("detach_kernel interface=$id driver=${driver.name} result=$rc")
    if(rc!=0&&rc!=-61)throw IOException("无法释放iPhone内核接口$id errno=${-rc}")
    if(rc==0)detached.add(id)
   }
   checkOpen();result=ops.configure(target);ops.log("configuration_switch_retry=$target result=$result")
  }
  if(result!=0)throw IOException("切换iPhone USB配置失败 errno=${-result}")
  changed=true;val actual=ops.current();ops.log("configuration_verified=$actual expected=$target")
  if(actual!=target)throw IOException("USB configuration mismatch actual=$actual expected=$target")
  inspect(target)
 }
 @Synchronized override fun close(){
  if(closed)return;closed=true;val before=original?:return
  try{
   var actual=ops.current()
   if(changed&&actual!=before){
    val bindings=inspect(actual);rejectUserSpace(bindings);val released=ArrayList<Int>()
    try{for((id,d) in bindings){if(d.absent||d.name.isEmpty())continue;val now=ops.driver(id);if(now.absent)continue;if(now.userSpace||now.error!=0||now.name!=d.name)throw IOException("USB restore ownership changed");val rc=ops.bind(id,false);if(rc==0)released.add(id)else if(rc!=-61)throw IOException("USB restore detach errno=${-rc}")}
     val rc=ops.configure(before);ops.log("restore_configuration=$before result=$rc");if(rc!=0)throw IOException("USB restore errno=${-rc}");actual=ops.current()
    }catch(e:Exception){for(id in released)runCatching{ops.bind(id,true)};throw e}
   }
   if(actual==before){
    // If the target was already active, interface claims may still have detached
    // its kernel driver. Restore the original binding, but never replace a new owner.
    val restoreIds=LinkedHashSet<Int>();restoreIds.addAll(detached)
    if(before==target)for((id,d) in originalBindings)if(d.error==0&&!d.userSpace&&d.name.isNotEmpty())restoreIds.add(id)
    for(id in restoreIds){val now=ops.driver(id)
     if(!now.absent){ops.log("restore_kernel_skipped interface=$id driver=${now.name} errno=${now.error}");continue}
     ops.log("restore_kernel interface=$id result=${ops.bind(id,true)}")
    }
   }
  }catch(e:Exception){ops.log("restore_incomplete=${e.message}; 拔插iPhone数据线可恢复USB初始状态")}
 }
}

class UsbConfigurationLease(private val connection:UsbDeviceConnection,configs:List<LegacyConfiguration>,target:Int,private val onDiagnostic:(String)->Unit={}):Closeable {
 private val state=UsbConfigurationTakeover(object:UsbConfigurationOps {
  override fun current()=LegacyUsb.currentConfiguration(connection)
  override fun configure(id:Int)=NativeUsb.configure(connection.fileDescriptor,id)
  override fun interfaces(config:Int)=configs.singleOrNull{it.id==config}?.descriptor?.interfaces?.map{it.id}?.distinct()?:throw IOException("USB config $config not in descriptors")
  override fun driver(id:Int)=LegacyUsb.driver(connection,id)
  override fun bind(id:Int,connect:Boolean)=NativeUsb.bind(connection.fileDescriptor,id,connect)
  override fun log(message:String)=onDiagnostic(message)
 },target)
 fun prepare()=state.prepare()
 override fun close(){try{state.close()}finally{connection.close()}}
}
