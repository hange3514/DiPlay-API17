package local.headunit.diplay;
/** Exact envelopes measured on MX9A0; no guessed volume or long-press mappings. */
public final class WheelKeyPolicy {
 private final long[] at={-10000,-10000,-10000,-10000,-10000,-10000};
 private final long[] androidAt={-10000,-10000,-10000,-10000,-10000,-10000};
 // 18/19 are the isPhoneHandleSeek branch variants of previous/next in the OEM McuControl.DecodeShortKey.
 // 93 is the OEM voice key (case 93 broadcasts {4,1,93} then tries com.byd.asr, absent on MX9A0).
 public static int decode(byte[] b){if(b==null||b.length!=3||b[0]!=4||b[1]!=1)return 0;switch(b[2]&255){case 4:return 1;case 18:return 1;case 3:return 2;case 19:return 2;case 94:return 3;case 95:return 4;case 93:return 5;default:return 0;}}
 /** Media action for an Android key code: 1=previous, 2=next, 0=not a wheel track key. */
 public static int androidAction(int key){return key==88?1:key==87?2:0;}
 /** Broadcast path: rejects its own 120ms echo and any press the Android key path already sent. */
 public synchronized boolean accept(int action,long now){if(action<1||action>5)return false;if(now>=at[action]&&now-at[action]<120)return false;if(now>=androidAt[action]&&now-androidAt[action]<900)return false;at[action]=now;return true;}
 public synchronized boolean duplicateAndroid(int key,long now){int action=androidAction(key);return action!=0&&now>=at[action]&&now-at[action]<900;}
 /** Android key-event path: rejects presses the broadcast already handled and its own 120ms echo. */
 public synchronized boolean acceptAndroid(int action,long now){if(action<1||action>5)return false;if(now>=at[action]&&now-at[action]<900)return false;if(now>=androidAt[action]&&now-androidAt[action]<120)return false;androidAt[action]=now;return true;}
}
