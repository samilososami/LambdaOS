#!/usr/bin/env python3
"""Release-sign Android target-files, then generate the clean-install Alpha 2 OTA.

Run inside the existing WSL source tree. Keys must be an ephemeral, owner-only
directory made by stage_release_keys.mjs. No credentials or secrets enter logs.
"""
import argparse, copy, hashlib, json, os, pathlib, re, shutil, subprocess, sys, tempfile, zipfile
from release_checks import validate_identity, without_addon_migration

if os.geteuid() == 0:
    os.execvp('runuser', ['runuser', '-u', 'kali', '--', sys.executable, __file__, *sys.argv[1:]])

def run(args, capture=False):
    return subprocess.run([str(a) for a in args], check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout

def fields(line):
    return dict(re.findall(r'(\w+)="([^"]*)"', line))

def sha(path):
    with open(path,'rb') as f:
        return hashlib.file_digest(f,'sha256').hexdigest()

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--source',default='/srv/lambdaos/src/lineage-22.2')
    p.add_argument('--keys',required=True)
    p.add_argument('--input',required=True)
    p.add_argument('--output',default='/srv/lambdaos/releases/0.1.0-alpha.2')
    a=p.parse_args()
    source=pathlib.Path(a.source).resolve(); keys=pathlib.Path(a.keys).resolve()
    alpha=pathlib.Path(__file__).resolve().parents[1]
    if keys.parent!=pathlib.Path('/dev/shm') or not re.fullmatch(r'lambda-release-[a-f0-9-]{36}',keys.name):
        raise RuntimeError('Refusing an unvalidated ephemeral-key directory')
    if keys.stat().st_uid!=os.getuid() or keys.stat().st_mode & 0o077:
        raise RuntimeError('Key directory must be owner-only')
    output=pathlib.Path(a.output).resolve();output.mkdir(parents=True,exist_ok=True)
    if any(output.glob('*.zip')):raise RuntimeError('Release output already contains ZIPs; choose a new candidate directory')
    manifest=json.loads((alpha/'config/signing/lambdaos-release-v1/manifest.json').read_text())
    known={k['name']:k['sha256'].replace(':','').lower() for k in manifest['keys']}
    for name in known:
        for suffix in ('.pk8','.pem','.x509.pem'):
            file=keys/(name+suffix)
            if not file.is_file() or file.stat().st_mode&0o077:raise RuntimeError('Missing or insecure signing file '+name+suffix)
        digest=run(['openssl','x509','-in',keys/(name+'.x509.pem'),'-noout','-fingerprint','-sha256'],True).strip().split('=')[-1].replace(':','').lower()
        if digest!=known[name]:raise RuntimeError('Certificate does not match pinned identity: '+name)
    os.chdir(source)
    bins=source/'out/host/linux-x86/bin'
    os.environ['PATH']=str(bins)+':'+os.environ['PATH']
    os.environ['TMPDIR']=str(output/'tmp');pathlib.Path(os.environ['TMPDIR']).mkdir(exist_ok=True)
    os.environ['JAVA_HOME']=str(source/'prebuilts/jdk/jdk21/linux-x86')
    os.environ['PATH']=os.environ['JAVA_HOME']+'/bin:'+os.environ['PATH']
    with zipfile.ZipFile(a.input) as z:
        if z.testzip():raise RuntimeError('Corrupt input target-files')
        names=set(z.namelist())
        apk_records=[fields(line) for line in z.read('META/apkcerts.txt').decode().splitlines() if line.strip()]
        apex_records=[fields(line) for line in z.read('META/apexkeys.txt').decode().splitlines() if line.strip()]
        misc=dict(line.split('=',1) for line in z.read('META/misc_info.txt').decode().splitlines() if '=' in line)
        if not any(n.endswith('/priv-app/LineageSetupWizard/LineageSetupWizard.apk') for n in names):raise RuntimeError('Missing native setup wizard')
        boot=[n for n in names if n.endswith('/media/bootanimation.zip')]
        if not boot or any(hashlib.sha256(z.read(n)).hexdigest()!='2b6acfdf1f9972b57f4ae31b0f41acfc02624e59bec1fbb3351b9e956b1b5155' for n in boot):raise RuntimeError('Approved boot animation not preserved')
        validate_identity(z)
    signed=output/'LambdaOS-0.1.0-alpha.2-beyond1lte-signed-target_files.zip'
    cmd=[bins/'sign_target_files_apks','-o','-d',keys]
    mappings={}
    for r in apk_records:
        cert=r['certificate']
        if cert in ('PRESIGNED','EXTERNAL'):continue
        old=cert.removesuffix('.x509.pem');name=pathlib.Path(old).name
        new='releasekey' if name in ('testkey','devkey') else name
        if new not in known:
            if any(pathlib.PurePosixPath(n).name.removesuffix('.gz')==r['name'] for n in names):
                raise RuntimeError('Unreviewed installed APK signing identity: '+old)
            # apkcerts also lists hundreds of non-installed CTS/test modules.
            # They remain unused; final verification audits every packaged APK.
            continue
        mappings[old]=new
    # Lineage includes its public update certificate as an extra recovery key.
    # This private alpha should trust the pinned Lambda release identity only.
    for old in (misc.get('extra_recovery_keys','')+' '+misc.get('extra_ota_keys','')).split():
        name=pathlib.Path(old).name
        if name not in ('lineage','testkey','devkey','releasekey'):
            raise RuntimeError('Unreviewed additional OTA/recovery certificate: '+old)
        mappings[old]='releasekey'
    for old,new in sorted(mappings.items()):cmd+=['-k',old+'='+str(keys/new)]
    apex_names={}
    for r in apex_records:
        if r['private_key']=='PRESIGNED':
            if r['name']!='com.android.apex.cts.shim.apex':raise RuntimeError('Unreviewed PRESIGNED APEX '+r['name'])
            continue
        name=r['name'].removesuffix('.apex')
        if name not in known:raise RuntimeError('Unreviewed APEX identity: '+name)
        apex_names[r['name']]=name
        cmd+=['--extra_apks',r['name']+'='+str(keys/name),'--extra_apex_payload_key',r['name']+'='+str(keys/(name+'.pem'))]
    for part in ('vbmeta','recovery'):
        if 'avb_'+part+'_key_path' in misc:
            cmd+=['--avb_'+part+'_key',keys/'verity.pem','--avb_'+part+'_algorithm','SHA256_RSA4096']
    # Samsung's target-files includes BOOTABLE_IMAGES caches. AOSP treats these
    # as already final, so they would bypass recovery key/fingerprint updates.
    # PREBUILT_IMAGES/boot.img takes the upstream re-sign path, preserving its
    # kernel/ramdisk; recovery is rebuilt from the fully available RECOVERY tree.
    with tempfile.TemporaryDirectory(prefix='sign-input-',dir=output/'tmp') as prepared_dir:
        prepared=pathlib.Path(prepared_dir)/'target-files.zip'
        cached_boot='BOOTABLE_IMAGES/boot.img';cached_recovery='BOOTABLE_IMAGES/recovery.img'
        with zipfile.ZipFile(a.input) as original:
            for required in (cached_boot,cached_recovery,'RECOVERY/kernel','RECOVERY/dtb',
                             'RECOVERY/recovery_dtbo','RECOVERY/RAMDISK/prop.default',
                             'RECOVERY/RAMDISK/system/etc/security/otacerts.zip'):
                if required not in original.namelist():raise RuntimeError('Missing expected source for boot/recovery rebuild: '+required)
            if 'PREBUILT_IMAGES/boot.img' in original.namelist():raise RuntimeError('Conflicting prebuilt boot image')
            run([bins/'zip2zip','-i',a.input,'-o',prepared,'-x',cached_boot,'-x',cached_recovery])
            with zipfile.ZipFile(prepared,'a',allowZip64=True) as updated:
                info=copy.copy(original.getinfo(cached_boot));info.filename='PREBUILT_IMAGES/boot.img'
                updated.writestr(info,original.read(cached_boot))
            with zipfile.ZipFile(prepared) as updated:
                expected=set(original.namelist())-{cached_boot,cached_recovery}|{'PREBUILT_IMAGES/boot.img'}
                if set(updated.namelist())!=expected:raise RuntimeError('Unexpected signing-input transformation')
                for member in updated.infolist():
                    old=original.getinfo(cached_boot if member.filename=='PREBUILT_IMAGES/boot.img' else member.filename)
                    if (member.CRC,member.file_size)!=(old.CRC,old.file_size):raise RuntimeError('Signing-input content changed unexpectedly')
        print('Signing target-files; rebuild recovery and re-sign the cached boot via upstream tools.',flush=True)
        run(cmd+[prepared,signed])
    ota=output/'LambdaOS-0.1.0-alpha.2-beyond1lte-clean-install.zip'
    normal=output/'candidate-with-addon-d.zip'
    run([bins/'ota_from_target_files','--block','-k',keys/'releasekey',signed,normal])
    # The standalone OTA tool can produce an already-clean installer. Otherwise
    # remove only the two audited addon.d calls, preserving every other statement.
    with tempfile.TemporaryDirectory(prefix='clean-',dir=output/'tmp') as work:
        work=pathlib.Path(work);unsigned=work/'unsigned.zip'
        updater='META-INF/com/google/android/updater-script'
        with zipfile.ZipFile(normal) as zin,zipfile.ZipFile(unsigned,'w',allowZip64=True) as zout:
            script=zin.read(updater).decode()
            clean_script,removed_calls=without_addon_migration(script)
            for info in zin.infolist():
                if info.filename.startswith('META-INF/') and info.filename.upper().endswith(('.RSA','.SF','MANIFEST.MF')):continue
                if info.filename==updater:
                    zout.writestr(info,clean_script);continue
                with zin.open(info) as src,zout.open(info,'w',force_zip64=True) as dest:shutil.copyfileobj(src,dest,1024*1024)
        run(['java','-Xmx4096m','-Djava.library.path='+str(source/'out/host/linux-x86/lib64'),'-jar',source/'out/host/linux-x86/framework/signapk.jar','-w',keys/'releasekey.x509.pem',keys/'releasekey.pk8',unsigned,ota])
    report={'identity':manifest['identity'],'status':'signed-awaiting-verification','device':'beyond1lte','version':'0.1.0-alpha.2','input_sha256':sha(a.input),'apk_key_mappings':mappings,'apex_key_mappings':apex_names,'signed_target_files':str(signed),'ota':str(ota),'ota_sha256':sha(ota),'preserved_bootanimation_sha256':'2b6acfdf1f9972b57f4ae31b0f41acfc02624e59bec1fbb3351b9e956b1b5155','firmware_policy':'preserve BL CP up_param EFS firmware','installed':False}
    report['addon_d_calls_removed']=removed_calls
    report['bootable_cache_handling']='boot moved to PREBUILT_IMAGES for re-signing; recovery cache excluded and recovery rebuilt from RECOVERY sources'
    (output/'signing-report.json').write_text(json.dumps(report,indent=2)+'\n')
    (output/(ota.name+'.sha256')).write_text(report['ota_sha256']+'  '+ota.name+'\n')
    # Keep the original candidate for diagnosis; it is not the installation deliverable.
    print('Signed OTA created; verification and hardware testing remain required.',flush=True)

if __name__=='__main__':main()
