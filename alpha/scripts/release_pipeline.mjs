#!/usr/bin/env node
// Run signing and verification on the existing builder, cleaning only the exact
// temporary signing directory created for this invocation. Never flashes a phone.
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync,execFileSync} from 'node:child_process';
const self=fileURLToPath(import.meta.url),here=path.dirname(self);
if(process.getuid?.()===0){const r=spawnSync('runuser',['-u','kali','--',process.execPath,self,...process.argv.slice(2)],{stdio:'inherit'});process.exit(r.status??1);}
const [input,output]=process.argv.slice(2);
if(!input?.startsWith('/srv/lambdaos/src/lineage-22.2/out/')||!input.endsWith('.zip')||!/^[a-zA-Z0-9_./-]+$/.test(input))throw new Error('Explicit builder target-files ZIP required.');
if(!output?.startsWith('/srv/lambdaos/releases/')||!/^[a-zA-Z0-9_./-]+$/.test(output)||output.includes('/../'))throw new Error('Explicit new release directory required.');
if(!process.env.LAMBDA_SSH_SOCKET)throw new Error('Authenticated SSH master required.');
const builder=path.join(here,'builder_exec.mjs');
function remote(command,inherit=true){return spawnSync(process.execPath,[builder,command],{stdio:inherit?'inherit':'pipe',encoding:'utf8'});}
let keys='';let status=1;
try{
  const preflight=remote(`test -s '${input}' && test ! -e '${output}'`);
  if(preflight.status!==0)throw new Error('Input missing or release directory already exists.');
  keys=execFileSync(process.execPath,[path.join(here,'stage_release_keys.mjs')],{encoding:'utf8'}).trim();
  if(!/^\/dev\/shm\/lambda-release-[a-f0-9-]{36}$/.test(keys))throw new Error('Invalid ephemeral directory.');
  const command=`set -e
ulimit -c 0
cleanup() { if [ -d '${keys}' ]; then rm -r -- '${keys}'; fi; }
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
python3 /srv/lambdaos/alpha/scripts/sign_release.py --keys '${keys}' --input '${input}' --output '${output}'
cleanup
test ! -e '${keys}'
printf 'Temporary signing files removed.\\n'
python3 /srv/lambdaos/alpha/scripts/verify_release.py --input '${input}' --release '${output}'`;
  const r=remote(command);status=r.status??1;
}finally{
  if(/^\/dev\/shm\/lambda-release-[a-f0-9-]{36}$/.test(keys)){
    const r=remote(`if [ -d '${keys}' ]; then rm -r -- '${keys}'; fi; test ! -e '${keys}'`,false);
    if(r.status!==0){console.error('Could not confirm cleanup of the ephemeral signing directory; inspect builder before continuing.');status=1;}
  }
}
process.exit(status);
