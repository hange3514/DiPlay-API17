package local.headunit.diplay

/** Only runs before any reader/writer starts; never resets an active projection. */
object NcmInitialization {
 fun activate(profile:Int, reset:()->Int, probe:()->Unit, select:()->Boolean,
              filter:()->Int, log:(String)->Unit) {
  var resetOk=false
  if(UsbStartupProfile.resetAlternate(profile)) {
   val rc=reset();resetOk=rc==0
   log("ncm alternate-zero result=$rc fallback=${!resetOk}")
  }
  probe()
  val early=UsbStartupProfile.filterBeforeActivate(profile)&&resetOk
  var filterOk=false
  if(early){val rc=filter();filterOk=rc==0;log("ncm pre-activate filter result=$rc")}
  // Selecting the live setting is mandatory even when optional reset/filter failed.
  if(!select())throw java.io.IOException("Cannot activate NCM data interface")
  log("ncm data interface activated profile=$profile reset=$resetOk")
  if(!early||!filterOk){val rc=filter();log("ncm post-activate filter result=$rc fallback=${rc!=0}")}
 }
}
