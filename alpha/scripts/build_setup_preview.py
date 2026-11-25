#!/usr/bin/env python3
"""Build only the non-privileged visual harness from production UI components."""
import os,pathlib,shutil,subprocess,sys,tempfile,zipfile
if os.geteuid()==0:os.execvp('runuser',['runuser','-u','kali','--',sys.executable,__file__,*sys.argv[1:]])
alpha=pathlib.Path(__file__).resolve().parents[1];sdk=pathlib.Path('/tools/codex/android-sdk');bt=sdk/'build-tools/35.0.0';jar=sdk/'platforms/android-35/android.jar';jdk=pathlib.Path('/usr/lib/jvm/java-21-openjdk-amd64')
def run(*a):subprocess.run([str(x) for x in a],check=True,env={**os.environ,'JAVA_HOME':str(jdk)})
out=alpha/'testing';target=out/'lambda-setup-visual-preview.apk'
with tempfile.TemporaryDirectory(prefix='ui-preview-',dir=out) as tmp:
    w=pathlib.Path(tmp)
    for sub in ('res/font','res/drawable','res/drawable-nodpi','generated','classes','dex'):(w/sub).mkdir(parents=True,exist_ok=True)
    app=alpha/'apps/SetupWizard'
    for sub in ('font','drawable','drawable-nodpi'):
        for f in (app/'res'/sub).glob('lambda*'):shutil.copy(f,w/'res'/sub/f.name)
        for f in (app/'res'/sub).glob('lamb_*'):shutil.copy(f,w/'res'/sub/f.name)
    run(bt/'aapt2','compile','--dir',w/'res','-o',w/'res.zip')
    run(bt/'aapt2','link','-o',w/'base.apk','-I',jar,'--manifest',out/'preview/AndroidManifest.xml','--custom-package','org.lineageos.setupwizard','--java',w/'generated','--min-sdk-version','29','--target-sdk-version','34',w/'res.zip')
    src=app/'src/org/lineageos/setupwizard'
    run(jdk/'bin/javac','--release','8','-classpath',jar,'-d',w/'classes',*[src/name for name in ('LambdaUi.java','LambdaBackground.java','LambdaWindow.java','LambdaLanguageWheel.java','LambdaAppGlyph.java')],out/'preview/LambdaPreviewActivity.java',*list((w/'generated').rglob('*.java')))
    run(jdk/'bin/jar','--create','--file',w/'classes.jar','-C',w/'classes','.')
    run(bt/'d8','--release','--min-api','29','--lib',jar,'--output',w/'dex',w/'classes.jar')
    with zipfile.ZipFile(w/'base.apk','a') as z:
        for f in (w/'dex').glob('*.dex'):z.write(f,f.name)
    run(bt/'zipalign','-f','4',w/'base.apk',w/'aligned.apk')
    run(bt/'apksigner','sign','--ks',alpha/'private/lambda-development.p12','--ks-key-alias','lambda-dev','--ks-pass','pass:android','--key-pass','pass:android','--out',target,w/'aligned.apk')
run(bt/'apksigner','verify',target)
print('Visual harness only, NOT the setup APK or ROM:',target)
