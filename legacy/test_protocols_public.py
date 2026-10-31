from pathlib import Path
import os,subprocess,zipfile
import build
P=build.ROOT.parents[4]/'research/DiPlay-public/shared/src/test/java/com/shilapi/xcertplay'
names={
 'compat':['Base64CompatTest'],
 'transport':['Ntb16CodecTest','EthernetIpv6CodecTest','UsbMuxFrameBufferTest','UsbReadQueuePolicyTest','Iap2WiredControlClientTest','Iap2ControlDeadlineTest','Iap2FileTransferReceiverTest'],
 'airplay':['MicrophonePacketizerTest','BplistCodecDateTest','AirPlayHidInputSemanticsTest','ScreenCodecTest','SafeAreaTest'],
 'media':['MediaCodecSupportTest'],
}
files=[P/folder/(name+'.kt') for folder,ns in names.items() for name in ns]
files=[f for f in files if f.exists()]
files+=list((build.ROOT/'tests').glob('*.kt'))
support=[build.ROOT/'tests/retired-probes/TlsSelfTest.kt']
cp=os.pathsep.join(str(x) for x in [build.BUILD/'core.jar',build.BUILD/'java.jar',build.TC/'android.jar',build.TC/'kotlin-stdlib.jar',build.TC/'junit.jar',build.TC/'hamcrest.jar',build.TC/'bcprov.jar',build.TC/'bctls.jar',build.TC/'bcutil.jar'])
args=['-no-stdlib','-no-reflect','-jvm-target','1.8','-classpath',cp,'-Xfriend-paths='+str(build.BUILD/'core.jar'),'-d',str(build.BUILD/'tests.jar'),*[str(f) for f in files+support]]
af=build.BUILD/'test.args';af.write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in args),encoding='utf-8')
build.run([build.JAVA,'-Dfile.encoding=UTF-8','-Xmx2g','-cp',build.TC/'*','org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(af)],'test-compile')
stub_classes=build.BUILD/'jvm-stubs';stub_classes.mkdir(exist_ok=True)
build.run([build.JAVA.with_name('javac.exe'),'-source','8','-target','8','-d',stub_classes,build.ROOT/'tests/stubs/android/util/Log.java'],'test-log-stub')
with zipfile.ZipFile(build.BUILD/'tests.jar','a') as jar:
 for stub in stub_classes.rglob('*.class'):jar.write(stub,stub.relative_to(stub_classes).as_posix())
testnames=[('local.headunit.diplay.' if f.parent.name=='tests' else 'com.shilapi.xcertplay.'+f.parent.name+'.')+f.stem for f in files]
build.run([build.JAVA,'-cp',str(build.BUILD/'tests.jar')+os.pathsep+cp,'org.junit.runner.JUnitCore',*testnames],'protocol-tests')
print((build.BUILD/'protocol-tests.log').read_text(encoding='utf-8',errors='replace'))
