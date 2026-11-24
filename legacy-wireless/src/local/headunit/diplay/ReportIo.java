package local.headunit.diplay;
import android.util.AtomicFile;
import java.io.*;
import org.json.*;

/** Optional USB-export copy and strictly bounded acknowledgement parsing. */
final class ReportIo {
 static JSONArray wireRows(JSONArray rows,String credential)throws Exception {
  JSONArray result=new JSONArray();
  for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String event=row.getString("event");if(event.length()<=3000){result.put(row);continue;}
   // Fragment encoding must not bypass the server's normal plaintext secret redaction.
   if(credential!=null&&!credential.isEmpty())event=event.replace(credential,"[redacted]");
   event=event.replaceAll("(?:sk-|tvly-)[A-Za-z0-9_-]{12,}|Bearer\\s+\\S+","[redacted]");
   byte[] bytes=event.getBytes("UTF-8");int parts=(bytes.length+1535)/1536;
   for(int n=0;n<parts;n++){int offset=n*1536;JSONObject part=new JSONObject().put("sourceId",row.getString("id")).put("encoding","utf8-base64").put("part",n).put("parts",parts).put("data",android.util.Base64.encodeToString(bytes,offset,Math.min(1536,bytes.length-offset),android.util.Base64.NO_WRAP));result.put(new JSONObject().put("time",row.getLong("time")).put("event","LOG_FRAGMENT "+part.toString()));}
  }
  return result;
 }
 static synchronized File backup(File folder,byte[] data)throws IOException {
  if(!folder.exists()&&!folder.mkdirs())throw new IOException("Cannot create report folder");
  File target=new File(folder,"last-report.json");AtomicFile file=new AtomicFile(target);FileOutputStream out=file.startWrite();
  try{out.write(data);file.finishWrite(out);return target;}catch(IOException e){file.failWrite(out);throw e;}
 }
 static String receipt(InputStream input,int expected)throws Exception {
  ByteArrayOutputStream body=new ByteArrayOutputStream();
  try{byte[] buffer=new byte[512];int count;while((count=input.read(buffer))!=-1){if(body.size()+count>4096)throw new IOException("Receipt too large");body.write(buffer,0,count);}}finally{input.close();}
  JSONObject ack=new JSONObject(new String(body.toByteArray(),"UTF-8"));String id=ack.getString("id");
  if(!id.matches("[0-9]{8}T[0-9]{6}Z-[a-f0-9]{12}")||ack.getInt("events")!=expected)throw new IOException("Invalid receipt");
  return id;
 }
 static synchronized void expireBackup(File folder,long now){
  for(String name:new String[]{"last-report.json","last-report.json.bak"}){File target=new File(folder,name);if(target.isFile()&&now-target.lastModified()>86400000L)target.delete();}
 }
}
