"""Read the same partition-based product identity Android init resolves at boot."""
import re, struct

def avb_image_payload(data):
    """Return original partition bytes, excluding a structurally valid AVB footer.

    This is for payload equality checks, not signature verification. avbtool
    must independently verify the hash descriptor and its enclosing trust chain.
    """
    if len(data)<64:
        raise RuntimeError('Truncated AVB image')
    magic,major,minor,size,offset,meta_size=struct.unpack('>4sIIQQQ28x',data[-64:])
    if (magic!=b'AVBf' or (major,minor)!=(1,0) or not 0<size<=offset
            or meta_size<256 or offset+meta_size>len(data)-64
            or data[offset:offset+4]!=b'AVB0'):
        raise RuntimeError('Unexpected AVB footer layout')
    return data[:size]

def without_addon_migration(script):
    """Accept an already-clean OTA or the known two single-line addon.d calls."""
    selected=[line for line in script.splitlines() if 'backuptool' in line]
    if not selected:
        return script,0
    pattern=r'^\s*run_program\("/tmp/install/bin/backuptool\.sh",\s*"(backup|restore)"\);\s*$'
    actions=[]
    for line in selected:
        match=re.fullmatch(pattern,line)
        if not match:
            raise RuntimeError('Unreviewed addon.d invocation: refusing to rewrite installer')
        actions.append(match.group(1))
    if sorted(actions)!=['backup','restore']:
        raise RuntimeError('Expected exactly one addon.d backup and restore')
    return '\n'.join(line for line in script.splitlines() if line not in selected)+'\n',2

def validate_no_userdata_wipe(script, metadata, entries, dynamic_ops):
    """Fail closed on destructive installer drift in this update-only release.

    This audits the generated installer, not whether every possible Android
    migration is compatible. Signatures and base/device compatibility are
    verified separately; hardware first boot remains a separate check.
    """
    fields=dict(line.split('=',1) for line in metadata.splitlines() if '=' in line)
    if fields.get('ota-wipe','no').strip().lower() not in ('no','false','0',''):
        raise RuntimeError('OTA requests a userdata wipe')
    active='\n'.join(line.split('#',1)[0] for line in script.splitlines())
    if re.search(r'\b(format|delete|delete_recursive|run_program)\s*\(',active):
        raise RuntimeError('Destructive or external installer action needs review')
    if re.search(r'/data(?:/|["\s])|/by-name/(userdata|metadata)\b',active,re.I):
        raise RuntimeError('Installer references a data-bearing partition')
    if any(re.search(r'(^|/)(userdata|metadata)\.(img|new\.dat|transfer\.list)',n,re.I) for n in entries):
        raise RuntimeError('OTA contains a data-bearing partition payload')
    if re.search(r'\b(userdata|metadata)\b',dynamic_ops,re.I):
        raise RuntimeError('Dynamic partition operations affect a data-bearing partition')
    return True

def product_properties(archive):
    values={}
    for name in archive.namelist():
        if not name.endswith('/build.prop'):
            continue
        for line in archive.read(name).decode(errors='replace').splitlines():
            if '=' not in line or line.lstrip().startswith('#'):
                continue
            key,value=line.split('=',1)
            if not (key.startswith('ro.lambda.') or re.fullmatch(r'ro\.product\.(?:(?:product|odm|vendor|system_ext|system)\.)?device',key) or key=='ro.product.property_source_order'):
                continue
            if key in values and values[key]!=value:
                raise RuntimeError('Conflicting product identity property: '+key)
            values[key]=value
    return values

def validate_identity(archive):
    values=product_properties(archive)
    order=values.get('ro.product.property_source_order','product,odm,vendor,system_ext,system').split(',')
    if any(part not in ('product','odm','vendor','system_ext','system') for part in order):
        raise RuntimeError('Unexpected Android product property source order')
    device=values.get('ro.product.device')
    if not device:
        device=next((values['ro.product.'+part+'.device'] for part in order if values.get('ro.product.'+part+'.device')),None)
    if device!='beyond1lte':
        raise RuntimeError('Resolved Android product device is not beyond1lte: '+str(device))
    if values.get('ro.lambda.name')!='LambdaOS' or values.get('ro.lambda.version')!='0.1.0-alpha.2':
        raise RuntimeError('Unexpected LambdaOS product identity')
    return values
