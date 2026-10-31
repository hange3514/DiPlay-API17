from pathlib import Path
import os, re, subprocess, sys, zipfile, shutil

ROOT=Path(__file__).resolve().parent
TC=ROOT.parents[2]/'toolchain'
JAVA=next((TC/'jdk21').glob('*/bin/java.exe'))
BUILD=ROOT/'build'; BUILD.mkdir(exist_ok=True)
def run(cmd, name):
    native=name in ('aapt','align','stable-aapt','stable-align')
    argv=[os.path.relpath(x,ROOT) if native and isinstance(x,Path) else str(x) for x in cmd]
    argv[0]=str(cmd[0])
    p=subprocess.run(argv,capture_output=True,cwd=ROOT if native else None)
    (BUILD/(name+'.log')).write_bytes(p.stdout+p.stderr)
    print(name, p.returncode, flush=True)
    if p.returncode:
        print((p.stdout+p.stderr).decode('utf-8','replace')[:10000].encode('ascii','backslashreplace').decode())
        raise SystemExit(p.returncode)
def compile_kotlin(files, output, extra=(), platform='android17.jar'):
    cp=os.pathsep.join((TC/n).as_posix() for n in [platform,'kotlin-stdlib.jar','annotations.jar','bcprov.jar','bctls.jar','bcutil.jar','jmdns.jar','junit.jar','hamcrest.jar'])
    args=['-no-stdlib','-no-reflect','-jvm-target','1.8','-Xlambdas=class','-classpath',cp,'-d',output.as_posix(),*extra,*[p.as_posix() for p in files]]
    argfile=BUILD/'kotlin.args';argfile.write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in args),'utf-8')
    run([JAVA,'-Dfile.encoding=UTF-8','-Xmx2g','-cp',TC/'*','org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],'compile')
if __name__=='__main__':
    if '--package-only' not in sys.argv:
        native=BUILD/'libdiplayusb.so'
        preserved=ROOT/'native/libdiplayusb-0.53.31.so'
        force_native='--rebuild-native' in sys.argv
        if preserved.is_file() and not force_native:
            shutil.copyfile(preserved,native)
            print('native preserved from 0.53.31',flush=True)
        else:
            # JNI's C declarations use no stdio types. Keep its complete ABI/header
            # and copyright, omitting that unused include for this sysroot-free build.
            jni=(JAVA.parents[1]/'include/jni.h').read_text(encoding='utf-8')
            (BUILD/'jni.h').write_text(jni.replace('#include <stdio.h>', '/* Unused stdio include omitted by legacy/build.py. */'),encoding='utf-8')
            run([TC/'zig/ziglang/zig.exe','cc','-target','arm-linux-android','-mcpu=cortex_a9','-mfloat-abi=softfp','-O2','-fno-vectorize','-fno-slp-vectorize','-ffreestanding','-fPIC','-fvisibility=hidden','-ffunction-sections','-fdata-sections','-Wl,--gc-sections','-fno-stack-protector','-fno-unwind-tables','-fno-asynchronous-unwind-tables','-nostdlib','-shared','-I',ROOT/'native','-I',BUILD,'-Wl,--hash-style=sysv','-Wl,-soname,libdiplayusb.so',ROOT/'native/usb.c',ROOT/'native/startup_usb.c',ROOT/'native/crypto.c',ROOT/'native/monocypher/monocypher.c',ROOT/'native/alsa_probe.c',TC/'libc-api17-arm.so','-o',native],'native')
        compile_kotlin(sorted((ROOT/'src').rglob('*.kt'))+sorted((ROOT/'src').rglob('*.java')),BUILD/'core.jar')
        classes=BUILD/'java'
        assert classes.resolve()==(ROOT/'build/java').resolve()
        if classes.exists():shutil.rmtree(classes)
        classes.mkdir()
        cp=os.pathsep.join(str(x) for x in [BUILD/'core.jar',TC/'android17.jar',TC/'kotlin-stdlib.jar'])
        run([JAVA.with_name('javac.exe'),'-encoding','UTF-8','-source','8','-target','8','-bootclasspath',TC/'android17.jar','-classpath',cp,'-d',classes,*sorted((ROOT/'src').rglob('*.java'))],'javac')
        with zipfile.ZipFile(BUILD/'java.jar','w') as z:
            for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes).as_posix())
        dex=BUILD/'dex';dex.mkdir(exist_ok=True)
        run([JAVA,'-Xmx3g','-cp',TC/'r8.jar','com.android.tools.r8.R8','--release','--min-api','17','--lib',TC/'android17.jar','--pg-conf',ROOT/'proguard.pro','--output',dex,'--pg-map-output',BUILD/'mapping.txt',BUILD/'core.jar',BUILD/'java.jar',TC/'kotlin-stdlib.jar',TC/'annotations.jar',TC/'bcprov.jar',TC/'bctls.jar',TC/'bcutil.jar',TC/'jmdns.jar'],'r8')
    workspace=ROOT.parents[4];bt=workspace/'headunit-probe/tools/build-tools/android-11'
    res=BUILD/'res';res.mkdir(exist_ok=True)
    run([bt/'aapt.exe','package','-f','-M',ROOT/'AndroidManifest.xml','-S',res,'-S',ROOT/'res','-A',ROOT/'assets','-I',workspace/'headunit-probe/tools/platform/android-4.4.2/android.jar','-F',BUILD/'unsigned.apk'],'aapt')
    with zipfile.ZipFile(BUILD/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:
        for f in (BUILD/'dex').glob('*.dex'):z.write(f,f.name)
        z.write(BUILD/'libdiplayusb.so','lib/armeabi-v7a/libdiplayusb.so')
    run([bt/'zipalign.exe','-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk'],'align')
    dist=ROOT/'dist';dist.mkdir(exist_ok=True)
    manifest=(ROOT/'AndroidManifest.xml').read_text(encoding='utf-8')
    match=re.search(r'android:versionName=["\']([^"\']+)["\']',manifest)
    if not match: raise SystemExit('AndroidManifest.xml has no versionName')
    safe_version=re.sub(r'[^A-Za-z0-9._-]+','_',match.group(1))
    apk=dist/('DiPlay_API17_'+safe_version+'.apk')
    run([JAVA,'-jar',bt/'lib/apksigner.jar','sign','--ks',workspace/'headunit-probe/probe-signing.jks','--ks-key-alias','probe','--ks-pass','pass:local-probe-build','--key-pass','pass:local-probe-build','--min-sdk-version','17','--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','false','--v4-signing-enabled','false','--out',apk,BUILD/'aligned.apk'],'sign')
    run([JAVA,'-jar',bt/'lib/apksigner.jar','verify','--min-sdk-version','17','--max-sdk-version','17','--verbose',apk],'verify')
    print('APK bytes',apk.stat().st_size)
