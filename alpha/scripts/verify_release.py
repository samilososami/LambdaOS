#!/usr/bin/env python3
"""Verify the release candidate, without installing or communicating with a phone."""
import argparse, gzip, hashlib, json, os, pathlib, re, shutil, stat, struct, subprocess, sys, tempfile, zipfile
from release_checks import validate_identity, avb_image_payload, validate_no_userdata_wipe
from verify_lockscreen_apk import EXPECTED as LOCKSCREEN_APKS, verify_apk as verify_lockscreen_apk
from verify_touch_manifest import validate as verify_touch_manifest
if os.geteuid()==0:
    os.execvp('runuser',['runuser','-u','kali','--',sys.executable,__file__,*sys.argv[1:]])

def run(args):
    try:return subprocess.check_output([str(x) for x in args],text=True,stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as error:
        raise RuntimeError('Verification command failed: '+str(args[0])+'\n'+error.output) from error
def sha_file(p):
    with open(p,'rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def record(s):return dict(re.findall(r'(\w+)="([^"]*)"',s))
def assert_true(value,message):
    if not value:raise RuntimeError(message)

def main():
    p=argparse.ArgumentParser();p.add_argument('--source',default='/srv/lambdaos/src/lineage-22.2');p.add_argument('--release',required=True);p.add_argument('--input',required=True)
    a=p.parse_args();source=pathlib.Path(a.source);release=pathlib.Path(a.release);alpha=pathlib.Path(__file__).resolve().parents[1]
    report=json.loads((release/'signing-report.json').read_text());signed=pathlib.Path(report['signed_target_files']);ota=pathlib.Path(report['ota'])
    manifest=json.loads((alpha/'config/signing/lambdaos-release-v1/manifest.json').read_text())
    pinned={k['name']:k['sha256'].replace(':','').lower() for k in manifest['keys']}
    bins=source/'out/host/linux-x86/bin';os.environ['PATH']=str(bins)+':'+os.environ['PATH']
    checks=[];apks=[];apexes=[];inner_apks=[];exceptions=[]
    with tempfile.TemporaryDirectory(prefix='verify-',dir=release/'tmp') as wd, zipfile.ZipFile(signed) as z, zipfile.ZipFile(a.input) as original:
        work=pathlib.Path(wd);names=z.namelist()
        assert_true(z.testzip() is None,'Target-files CRC failure');checks.append('target-files ZIP CRC')
        touch_manifest=work/'touch-manifest.xml'
        touch_manifest.write_bytes(z.read('VENDOR/etc/vintf/manifest.xml'))
        verify_touch_manifest(touch_manifest)
        checks.append('packaged S10 touch AIDL v1 declarations match supported driver interfaces')
        certs={r['name']:r for r in (record(l) for l in original.read('META/apkcerts.txt').decode().splitlines()) if r}
        amap={r['name']:r for r in (record(l) for l in original.read('META/apexkeys.txt').decode().splitlines()) if r}
        def signer(file):
            result=run([bins/'apksigner','verify','--min-sdk-version','28','--print-certs',file])
            found=re.search(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)',result)
            assert_true(found is not None,'Missing certificate fingerprint '+str(file));return found.group(1).lower()
        for n in names:
            if n.endswith('.apk'):
                assert_true(not any(bad in n for bad in ('PrebuiltGmsCore','GoogleServicesFramework','Phonesky','GoogleSetupWizard')),'Unexpected Google Apps '+n)
                f=work/'current.apk';f.write_bytes(z.read(n));fingerprint=signer(f);basename=pathlib.PurePosixPath(n).name
                needed={'Settings.apk':(b'Lcom/android/settings/LambdaSetupBackground;',
                                        b'Lcom/android/settings/LambdaNativeUi;',
                                        b'setDefaultFocusHighlightEnabled',
                                        b'FingerprintEnrollEnrolling'),
                        'LineageSetupWizard.apk':(b'Lorg/lineageos/setupwizard/LambdaSetupActivity;',)}.get(basename)
                if needed:
                    with zipfile.ZipFile(f) as apk_zip:
                        dex=b''.join(apk_zip.read(d) for d in apk_zip.namelist()
                                     if re.fullmatch(r'classes\d*\.dex',d))
                        assert_true(all(marker in dex for marker in needed),
                                    'Required Lambda runtime marker stripped from '+basename)
                if basename in LOCKSCREEN_APKS:
                    verify_lockscreen_apk(f, basename, bins/'aapt2')
                meta=certs.get(basename,{});assert_true(meta,'Missing APK certificate metadata '+n)
                if meta.get('certificate')=='PRESIGNED':
                    assert_true(z.read(n)==original.read(n),'PRESIGNED APK changed: '+n)
                    exceptions.append({'path':n,'reason':'upstream PRESIGNED; unchanged bytes; signature verified','certificate':fingerprint})
                else:
                    key=report['apk_key_mappings'].get(meta.get('certificate','').removesuffix('.x509.pem'))
                    assert_true(key in pinned and fingerprint==pinned[key],'Incorrect APK identity '+n)
                apks.append({'path':n,'sha256':hashlib.sha256(z.read(n)).hexdigest(),'certificate':fingerprint})
            if n.endswith(('.apex','.capex')) and not n.startswith('META/'):
                compressed=n.endswith('.capex')
                container=work/('current.capex' if compressed else 'current.apex')
                container.write_bytes(z.read(n));fingerprint=signer(container)
                basename=pathlib.PurePosixPath(n).name
                if compressed:basename=basename.removesuffix('.capex')+'.apex'
                if basename=='com.android.apex.cts.shim.apex':
                    assert_true(z.read(n)==original.read(n),'CTS shim must remain byte-identical')
                    exceptions.append({'path':n,'reason':'AOSP PRESIGNED CTS compatibility shim; unchanged','certificate':fingerprint});continue
                key=report['apex_key_mappings'].get(basename)
                assert_true(key in pinned and fingerprint==pinned[key],'Incorrect APEX container identity '+n)
                f=container;inner_container_fingerprint=fingerprint
                if compressed:
                    f=work/'decompressed.apex'
                    # deapexer refuses overwrites. This is only our scratch output.
                    if f.exists():f.unlink()
                    run([bins/'deapexer','decompress','--input',container,'--output',f])
                    inner_container_fingerprint=signer(f)
                    assert_true(inner_container_fingerprint==pinned[key],'Incorrect decompressed APEX container identity '+n)
                with zipfile.ZipFile(f) as az:
                    payload=work/'payload.img';payload.write_bytes(az.read('apex_payload.img'))
                    embedded=az.read('apex_pubkey')
                public=work/'public.pem';public.write_text(run(['openssl','x509','-in',alpha/'config/signing/lambdaos-release-v1'/(key+'.x509.pem'),'-pubkey','-noout']))
                avbpub=work/'public.avbpubkey';run([bins/'avbtool','extract_public_key','--key',public,'--output',avbpub])
                assert_true(embedded==avbpub.read_bytes(),'Incorrect embedded APEX public key '+n)
                run([bins/'avbtool','verify_image','--image',payload,'--key',public])
                # A signed APEX container alone does not establish the identity
                # of APKs inside it. Extract and audit those certificates too.
                with tempfile.TemporaryDirectory(prefix='apex-',dir=work) as contents:
                    contents=pathlib.Path(contents)
                    run([bins/'deapexer','--debugfs_path',bins/'debugfs_static',
                         '--fsckerofs_path',bins/'fsck.erofs','extract',f,contents/'new'])
                    old_contents=None
                    for apk in (contents/'new').rglob('*.apk'):
                        relative=apk.relative_to(contents/'new');meta=certs.get(apk.name,{})
                        assert_true(meta,'Missing inner APK metadata: '+n+'!/'+str(relative))
                        inner_fingerprint=signer(apk)
                        if meta.get('certificate')=='PRESIGNED':
                            if old_contents is None:
                                old_apex=contents/'original.apex'
                                if compressed:
                                    old_container=contents/'original.capex';old_container.write_bytes(original.read(n))
                                    run([bins/'deapexer','decompress','--input',old_container,'--output',old_apex])
                                else:old_apex.write_bytes(original.read(n))
                                old_contents=contents/'old'
                                run([bins/'deapexer','--debugfs_path',bins/'debugfs_static',
                                     '--fsckerofs_path',bins/'fsck.erofs','extract',old_apex,old_contents])
                            assert_true(apk.read_bytes()==(old_contents/relative).read_bytes(),'PRESIGNED inner APK changed')
                            exceptions.append({'path':n+'!/'+str(relative),'reason':'upstream PRESIGNED inner APK; unchanged; signature verified','certificate':inner_fingerprint})
                        else:
                            inner_key=report['apk_key_mappings'].get(meta.get('certificate','').removesuffix('.x509.pem'))
                            assert_true(inner_key in pinned and inner_fingerprint==pinned[inner_key],'Incorrect inner APK identity '+n+'!/'+str(relative))
                        inner_apks.append({'path':n+'!/'+str(relative),'certificate':inner_fingerprint})
                apexes.append({'path':n,'compressed':compressed,'container_certificate':fingerprint,'decompressed_container_certificate':inner_container_fingerprint,'payload_signature':'verified'})
        assert_true(any('/Lambda/' in v['path'] for v in apks),'Lambda APK missing')
        assert_true(any('/LineageSetupWizard/' in v['path'] for v in apks),'Setup APK missing')
        checks += ['all packaged APK signatures','native setup and Settings background retained in DEX',
                   'Lambda wallpaper, Keyguard clocks and emergency resources/runtime packaged',
                   'APEX and CAPEX outer/inner container signatures','APEX payload AVB signatures','APKs embedded inside APEX payloads']
        boot=[n for n in names if n.endswith('/media/bootanimation.zip') or n.endswith('/media/bootanimation-dark.zip')]
        assert_true(any(n.endswith('/media/bootanimation.zip') for n in boot),'Boot animation missing')
        for n in boot:
            data=z.read(n)
            if stat.S_ISLNK(z.getinfo(n).external_attr>>16):
                assert_true(n.endswith('/bootanimation-dark.zip') and data==b'bootanimation.zip','Unexpected animation symlink')
                data=z.read(str(pathlib.PurePosixPath(n).parent/'bootanimation.zip'))
            assert_true(hashlib.sha256(data).hexdigest()==report['preserved_bootanimation_sha256'],'Boot animation mismatch: '+n)
        checks.append('approved normal/dark bootanimation byte identity')
        props='\n'.join(z.read(n).decode(errors='replace') for n in names if n.endswith('/build.prop'))
        validate_identity(z)
        assert_true('ro.build.tags=release-keys' in props,'Missing release-keys tag');checks.append('beyond1lte LambdaOS alpha.2 identity')
        assert_true(re.search(r'^ro\.adb\.secure=1$',props,re.M),'Secure ADB host authentication missing')
        assert_true(not re.search(r'^ro\.adb\.secure=0$',props,re.M),'Conflicting insecure ADB property')
        adbkey=z.read('PRODUCT/etc/security/adb_keys')
        assert_true(hashlib.sha256(adbkey).hexdigest()=='274b78fb8d23ce5d5fa93c576b7c1311c28b1e83c272d82ffcb41dedc12b27a0','ADB host authorization changed')
        assert_true(len(adbkey.decode().splitlines())==1,'Unexpected authorized ADB host count')
        adbinit=z.read('PRODUCT/etc/init/init.lambda.adb.rc')
        assert_true(adbinit==(alpha/'vendor/lambda/config/init.lambda.adb.rc').read_bytes(),'USB ADB init configuration changed')
        assert_true(b'tcp.port' not in adbinit and b'mtp,adb' in adbinit,'Unexpected ADB transport')
        checks.append('secure USB ADB with the pinned authorized host')
        ota_certs=[n for n in names if n.endswith('/etc/security/otacerts.zip')]
        assert_true(ota_certs,'Missing OTA trust store')
        for n in ota_certs:
            store=work/'otacerts.zip';store.write_bytes(z.read(n))
            with zipfile.ZipFile(store) as trust:
                for member in trust.namelist():
                    cert=work/'ota.pem';cert.write_bytes(trust.read(member))
                    got=run(['openssl','x509','-in',cert,'-noout','-fingerprint','-sha256']).strip().split('=')[-1].replace(':','').lower()
                    assert_true(got==pinned['releasekey'],'Unexpected OTA trust certificate')
        checks.append('OTA trust store uses Lambda releasekey')
        # Android 15 recovery reads otacerts.zip, not the historical res/keys
        # C structure. The trust store loop above verifies every contained cert.
        recovery_keys=[n for n in ota_certs if n.startswith('RECOVERY/RAMDISK/')]
        assert_true(recovery_keys,'Missing recovery OTA trust store')
        checks.append('recovery public key matches release identity')
        image_dir=work/'images';image_dir.mkdir()
        for n in names:
            if n.startswith('IMAGES/') and n.endswith('.img'):
                with z.open(n) as src,open(image_dir/pathlib.PurePosixPath(n).name,'wb') as dst:shutil.copyfileobj(src,dst,1024*1024)
        for part in ('recovery','vbmeta'):
            name='IMAGES/'+part+'.img'
            assert_true(name in names,'Missing required boot-chain image: '+name)
            file=image_dir/(part+'.img')
            pub=work/'verity-public.pem';pub.write_text(run(['openssl','x509','-in',alpha/'config/signing/lambdaos-release-v1/verity.x509.pem','-pubkey','-noout']))
            run([bins/'avbtool','verify_image','--image',file,'--key',pub])
            shutil.copyfile(file,release/file.name)
        checks.append('recovery and vbmeta signatures')
        # beyond1lte uses Android boot header v1: its DTB lives in a separate
        # partition, not the embedded DTB field introduced with header v2.
        for part in ('dtb','dtbo'):
            name='IMAGES/'+part+'.img'
            assert_true(name in names and name in original.namelist(),'Missing device-tree partition: '+name)
            before=original.read(name);after=z.read(name)
            if part=='dtb':
                assert_true(after==before,'Presigned DTB unexpectedly changed')
                exceptions.append({'path':name,'reason':'upstream presigned DTB; unchanged bytes; AVB verified',
                                   'sha256':hashlib.sha256(after).hexdigest()})
            else:
                # Signing replaces the build fingerprint in AVB metadata, not
                # the device-tree overlay itself. Compare the original payload.
                assert_true(avb_image_payload(after)==avb_image_payload(before),
                            'Device-tree overlay payload unexpectedly changed')
                info=run([bins/'avbtool','info_image','--image',image_dir/(part+'.img')])
                assert_true('test-keys' not in info and '/release-keys' in info,'Stale DTBO fingerprint')
            run([bins/'avbtool','verify_image','--image',image_dir/(part+'.img')])
        # Inspect the actual image ramdisk as well as target-files metadata.
        # Reading cpio to stdout does not create its device nodes on the builder.
        for part in ('boot','recovery'):
            unpacked=work/(part+'-unpacked');unpacked.mkdir()
            file=image_dir/(part+'.img')
            info=run([bins/'avbtool','info_image','--image',file])
            assert_true('test-keys' not in info and '/release-keys' in info,'Stale boot-chain fingerprint: '+part)
            old_file=work/(part+'-original.img')
            old_file.write_bytes(original.read('BOOTABLE_IMAGES/'+part+'.img'))
            for image_file in (old_file,file):
                with open(image_file,'rb') as image_stream:header=image_stream.read(44)
                assert_true(header[:8]==b'ANDROID!' and struct.unpack_from('<I',header,40)[0]==1,
                            'Unexpected beyond1lte boot image header: '+str(image_file))
            old_unpacked=work/(part+'-original-unpacked');old_unpacked.mkdir()
            run([bins/'unpack_bootimg','--boot_img',old_file,'--out',old_unpacked])
            run([bins/'unpack_bootimg','--boot_img',file,'--out',unpacked])
            components={p.name for p in unpacked.iterdir() if p.is_file()}
            old_components={p.name for p in old_unpacked.iterdir() if p.is_file()}
            assert_true(components==old_components and {'kernel','ramdisk'}<=components,
                        'Boot image component layout changed: '+part)
            assert_true('dtb' not in components,'Unexpected embedded DTB in header-v1 image: '+part)
            assert_true((unpacked/'kernel').read_bytes()==original.read(part.upper()+'/kernel'),
                        'Original kernel source mismatch: '+part)
            for component in components:
                if part=='recovery' and component=='ramdisk':continue  # OTA trust store is intentionally updated.
                assert_true((unpacked/component).read_bytes()==(old_unpacked/component).read_bytes(),
                            'Boot component unexpectedly changed: '+part+'/'+component)
            if part=='recovery':
                ramdisk=(unpacked/'ramdisk').read_bytes()
                assert_true(ramdisk.startswith(b'\x1f\x8b'),'Unexpected recovery ramdisk compression')
                archive=gzip.decompress(ramdisk)
                listing=subprocess.run(['cpio','-it','--quiet'],input=archive,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout.decode().splitlines()
                members=[n for n in listing if n.removeprefix('./')=='system/etc/security/otacerts.zip']
                assert_true(len(members)==1,'Missing/duplicate OTA trust store in actual recovery ramdisk')
                contents=subprocess.run(['cpio','-i','--quiet','--to-stdout',members[0]],input=archive,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout
                assert_true(contents==z.read('RECOVERY/RAMDISK/system/etc/security/otacerts.zip'),'Actual recovery ramdisk has stale OTA keys')
        recovery_copies=[n for n in ('VENDOR/etc/recovery.img','SYSTEM/etc/recovery.img') if n in names]
        assert_true(recovery_copies,'Expected full recovery image in system/vendor payload')
        for n in recovery_copies:
            assert_true(hashlib.sha256(z.read(n)).hexdigest()==sha_file(image_dir/'recovery.img'),'Embedded recovery image does not match verified recovery')
        checks += ['boot/recovery kernels, boot ramdisk and device-tree payloads preserved','release boot-chain fingerprints','actual recovery ramdisk OTA trust and embedded recovery image']
        # AOSP whole-file OTA signature covers all bytes except comment and its length.
        with open(ota,'rb') as f:
            f.seek(-6,2);tail=f.read(6);sig_start,magic,comment=struct.unpack('<H2sH',tail)
            assert_true(magic==b'\xff\xff' and sig_start>6 and sig_start<=comment,'Invalid OTA signature footer')
            f.seek(-sig_start,2);sig=f.read(sig_start-6);(work/'signature.der').write_bytes(sig)
            length=ota.stat().st_size-comment-2;f.seek(0)
            with open(work/'signed-data.bin','wb') as dst:
                remaining=length
                while remaining:
                    block=f.read(min(1024*1024,remaining));assert_true(block,'Truncated OTA');dst.write(block);remaining-=len(block)
        run(['openssl','cms','-verify','-binary','-inform','DER','-in',work/'signature.der','-content',work/'signed-data.bin','-noverify','-out','/dev/null'])
        cert_text=run(['openssl','pkcs7','-inform','DER','-in',work/'signature.der','-print_certs'])
        (work/'signer.pem').write_text(cert_text)
        got=run(['openssl','x509','-in',work/'signer.pem','-noout','-fingerprint','-sha256']).strip().split('=')[-1].replace(':','').lower()
        assert_true(got==pinned['releasekey'],'Wrong whole-file OTA signer');checks.append('whole-file OTA CMS signature and pinned releasekey')
        with zipfile.ZipFile(ota) as oz:
            assert_true(oz.testzip() is None,'OTA ZIP CRC failure')
            script=oz.read('META-INF/com/google/android/updater-script').decode()
            assert_true('beyond1lte' in script,'Missing installation model assertion')
            assert_true('backuptool.sh' not in script,'Unexpected addon.d migration')
            validate_no_userdata_wipe(script,
                oz.read('META-INF/com/android/metadata').decode(), oz.namelist(),
                oz.read('dynamic_partitions_op_list').decode())
            for entry in oz.namelist():assert_true(not re.search(r'(^|/)(sboot|modem|up_param|efs|sec_efs|cpefs|dqmdbg|bootloader|radio)(\.|/|$)',entry,re.I),'Firmware/EFS payload present: '+entry)
            for line in script.splitlines():
                if any(token in line for token in ('package_extract_file','write_raw_image','format(','block_image_update')):
                    assert_true(not re.search(r'/by-name/(EFS|SEC_EFS|CPEFS|UP_PARAM|RADIO|SBOOT|BOOTLOADER|DQMDBG)\b',line,re.I),'Forbidden firmware write')
            (release/'updater-script.txt').write_text(script)
        checks += ['latest Lambda setup and fingerprint runtime markers',
                   'OTA ZIP CRC','clean-install addon.d exclusion',
                   'no userdata wipe request, payload or destructive installer action',
                   'model assertion and firmware preservation']
    report.update(status='verified-package-not-flashed',checks=checks,apk_count=len(apks),apex_count=len(apexes),inner_apk_count=len(inner_apks),apks=apks,apexes=apexes,inner_apks=inner_apks,exceptions=exceptions,ota_sha256=sha_file(ota),hardware_validation='pending manual installation and S10 first boot')
    (release/'verification-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ('status','device','version','ota_sha256','apk_count','apex_count','checks')},indent=2))

if __name__=='__main__':main()
