package local.headunit.diplay

object UsbStartupProfile {
 const val DEFAULT=3
 @JvmStatic fun valid(id:Int)=if(id in 0..10)id else 0
 @JvmStatic fun title(id:Int)=when(id){
  6->"同步发送对照";7->"持续排队接收首包";8->"提前同步接收首包";9->"提前持续接收首包";10->"连续读取网络状态";5->"接口复位后先设置过滤器";1->"旧同步发送";2->"网络通知后发送";3->"数据接口重新初始化";4->"网络通知后刷新过滤设置";else->"当前持续排队方案"
 }
 fun queued(id:Int)=id!=1&&id!=6&&id!=7&&id!=9
 fun waitCarrier(id:Int)=id==2||id==4
 fun resetAlternate(id:Int)=id==3||id==5||id in 6..10
 fun filterBeforeActivate(id:Int)=id==5
 fun queuedReceive(id:Int)=id==7||id==9
 fun earlyReceive(id:Int)=id==8||id==9
 fun continuousStatus(id:Int)=id==10
 fun refreshFilter(id:Int)=id==4
}
