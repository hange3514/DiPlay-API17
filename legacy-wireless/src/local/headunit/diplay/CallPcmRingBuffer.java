package local.headunit.diplay;

import java.util.Arrays;

/** Bounded, synchronized PCM tail buffer. It has no file or network behavior. */
final class CallPcmRingBuffer {
 static final int MAX_SECONDS=3,MAX_BYTES=576000;
 static final class Snapshot {final byte[] pcm;final int sampleRate,channels;Snapshot(byte[] p,int r,int c){pcm=p;sampleRate=r;channels=c;}}
 private byte[] data;private int rate,channels,size,writeAt;
 synchronized void append(byte[] pcm,int sampleRate,int channelCount){
  if(pcm==null||sampleRate<8000||sampleRate>48000||(channelCount!=1&&channelCount!=2))return;
  if(data==null){rate=sampleRate;channels=channelCount;data=new byte[Math.min(sampleRate*channelCount*2*MAX_SECONDS,MAX_BYTES)];}
  if(rate!=sampleRate||channels!=channelCount)return;
  int frame=channels*2,n=pcm.length-pcm.length%frame,cap=data.length;if(n>=cap){System.arraycopy(pcm,n-cap,data,0,cap);writeAt=0;size=cap;return;}
  int first=Math.min(n,cap-writeAt);System.arraycopy(pcm,0,data,writeAt,first);if(n>first)System.arraycopy(pcm,first,data,0,n-first);writeAt=(writeAt+n)%cap;size=Math.min(cap,size+n);
 }
 synchronized boolean hasAtLeast(int minBytes){return size>=minBytes;}
 synchronized boolean hasUsefulAudio(){return rate>0&&size>=Math.max(4096,rate*channels);}
 synchronized int size(){return size;}
 synchronized Snapshot take(int minBytes){if(data==null||size<minBytes)return null;byte[] out=new byte[size];if(size<data.length)System.arraycopy(data,0,out,0,size);else{int first=data.length-writeAt;System.arraycopy(data,writeAt,out,0,first);if(writeAt>0)System.arraycopy(data,0,out,first,writeAt);}Snapshot s=new Snapshot(out,rate,channels);clear();return s;}
 synchronized Snapshot takeUsefulAudio(){return hasUsefulAudio()?take(Math.max(4096,rate*channels)):null;}
 synchronized void clear(){if(data!=null)Arrays.fill(data,(byte)0);data=null;rate=0;channels=0;size=0;writeAt=0;}
}
