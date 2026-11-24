package local.headunit.diplay;
import java.io.*;
import java.util.UUID;

/** Per-request temporary files and content-addressed installer paths prevent Activity races. */
final class UpdateFiles {
 static File part(File folder)throws IOException {
  if(folder==null||(!folder.isDirectory()&&!folder.mkdirs()))throw new IOException("外部存储不可用");
  return new File(folder,"DiPlay-"+UUID.randomUUID().toString()+".part");
 }
 static File target(File folder,String sha)throws IOException {
  if(!sha.matches("[a-f0-9]{64}"))throw new IOException("版本信息无效");
  return new File(folder,"DiPlay-"+sha+".apk");
 }
 static void requireSpace(File folder,long size)throws IOException { requireSpace(folder,size,OnlineUpdate.MAX_APK); }
 static void requireSpace(File folder,long size,long max)throws IOException {
  if(size<1||size>max)throw new IOException("invalid package size");
  long total=folder.getTotalSpace(),available=folder.getUsableSpace();
  if(total>0&&available<size+1024*1024L)throw new IOException("insufficient storage");
 }
 static void promote(File part,File target)throws IOException {
  // Android's rename replaces an existing file atomically. Keep the old valid APK
  // intact if the filesystem rejects the move (e.g. read-only/unavailable storage).
  if(!part.renameTo(target))throw new IOException("无法保存安装包");
 }
 static void expire(File folder,long now) {
  File[] files=folder.listFiles();if(files==null)return;
  for(File f:files){String name=f.getName();boolean owned=name.matches("DiPlay-[a-f0-9-]{36}\\.part")||name.matches("DiPlay-[a-f0-9]{64}\\.apk")||name.equals("DiPlay-update.part")||name.equals("DiPlay-update.apk");if(owned&&f.isFile()&&now-f.lastModified()>86400000L)f.delete();}
 }
}
