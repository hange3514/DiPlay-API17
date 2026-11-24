package local.headunit.diplay;
import android.content.Context;import android.media.AudioRecord;
public final class CarMicrophone {
 public static String start(AudioRecord recorder,Context context)throws Exception {
  if(context!=null&&AudioRouting.secondMusic(context)==10){RecorderNativeStart nativeStart=null;try{RecorderNativeStart candidate=new RecorderNativeStart(AudioRecord.class);if(candidate.hasVendorHook())nativeStart=candidate;}catch(Exception ignored){}
   if(nativeStart!=null){nativeStart.start(recorder);return "audited_native_start_without_oem_focus";}
   recorder.startRecording();return "public_start_vendor_hook_unavailable";
  }
  recorder.startRecording();return "public_start_other_firmware";
 }
}
