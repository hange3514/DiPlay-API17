package local.headunit.diplay;
import java.io.*;
import java.util.zip.GZIPInputStream;
import org.json.*;

/** Passive prerequisites only. Never launches su, a shell, a syscall exploit or a mount command. */
final class RootReadiness {
 static JSONObject collect()throws Exception {
  JSONObject out=new JSONObject().put("scope","read_only_prerequisites_not_vulnerability_or_root_test").put("osVersion",System.getProperty("os.version","")).put("uid",android.os.Process.myUid()).put("exploitExecuted",false).put("patchState","unknown_vendor_backports_require_source_or_image_review");
  JSONObject files=new JSONObject();for(String path:new String[]{"/proc/version","/proc/sys/vm/mmap_min_addr","/proc/sys/kernel/kptr_restrict","/proc/sys/kernel/randomize_va_space","/proc/sys/kernel/yama/ptrace_scope","/sys/fs/selinux/enforce"}){try{files.put(path,read(new FileInputStream(path),4096));}catch(IOException e){files.put(path,"unavailable_or_denied");}}
  out.put("kernelEvidence",files);
  JSONObject process=new JSONObject();try{for(String line:read(new FileInputStream("/proc/self/status"),16384).split("\n")){if(line.matches("^(Uid|Gid|CapInh|CapPrm|CapEff|CapBnd|Seccomp|NoNewPrivs):.*")){int at=line.indexOf(':');process.put(line.substring(0,at),line.substring(at+1).trim());}}}catch(IOException e){process.put("status","unavailable_or_denied");}out.put("process",process);
  JSONObject config=new JSONObject();try{FileInputStream raw=new FileInputStream("/proc/config.gz");try{String text=read(new GZIPInputStream(raw),262144);for(String line:text.split("\n")){if(line.matches("^(# )?CONFIG_(FUTEX|FUTEX_PI|RT_MUTEXES|CPU_USE_DOMAINS|ARM|ARM_THUMB|OABI_COMPAT|AEABI|IKCONFIG|IKCONFIG_PROC|SECURITY|SECURITY_SELINUX|SECURITY_DMESG_RESTRICT|DEVMEM|STRICT_DEVMEM|KALLSYMS|KALLSYMS_ALL|MODULES|MODULE_SIG|USER_NS|PID_NS)(=.*| is not set)$"))config.put(line.split("[= ]")[line.startsWith("# ")?1:0],line);}config.put("status","read");}finally{raw.close();}}catch(IOException e){config.put("status","unavailable_or_denied");}out.put("kernelConfig",config);
  JSONArray access=new JSONArray();for(String path:new String[]{"/proc/self/mem","/proc/kallsyms","/proc/kcore","/dev/mem","/dev/kmem","/dev/ashmem","/dev/ion","/dev/pmem","/dev/mxc_vpu","/system/bin/run-as","/system/bin/su","/system/xbin/su"})access.put(AutomationEvidence.file(path));out.put("access",access);
  JSONArray partitions=new JSONArray();for(String path:new String[]{"/dev/block/by-name","/dev/block/platform"}){File dir=new File(path);File[] children=dir.listFiles();JSONObject d=AutomationEvidence.file(path);JSONArray names=new JSONArray();if(children!=null)for(int i=0;i<Math.min(32,children.length);i++)names.put(children[i].getName());d.put("names",names);partitions.put(d);}out.put("partitionDirectories",partitions);
  return out;
 }
 static String read(InputStream input,int limit)throws IOException {
  try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[2048];while(out.size()<limit){int n=input.read(b,0,Math.min(b.length,limit-out.size()));if(n<0)break;out.write(b,0,n);}return new String(out.toByteArray(),"UTF-8");}finally{input.close();}
 }
}
