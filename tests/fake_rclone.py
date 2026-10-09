#!/usr/bin/env python3
"""Minimal rclone test double for local integration tests only."""
import hashlib, json, os, sys
from pathlib import Path

args = sys.argv[1:]
state_path = Path(os.environ['FAKE_RCLONE_STATE'])
config_path = os.environ['FAKE_RCLONE_CONFIG']
if not state_path.exists():
    state_path.write_text(json.dumps({'folders': [], 'files': []}), encoding='utf-8')
state = json.loads(state_path.read_text(encoding='utf-8'))
parent_override = None
while args and args[0].startswith('--'):
    if args[0] == '--config':
        args = args[2:]
    elif args[0] == '--drive-root-folder-id':
        parent_override = args[1]
        args = args[2:]
    else:
        args = args[1:]

def save():
    state_path.write_text(json.dumps(state), encoding='utf-8')

def parent_id():
    return parent_override or 'root'

def output(value):
    print(json.dumps(value))

if not args:
    sys.exit(2)
cmd = args[0]
if cmd == 'config' and len(args) > 1 and args[1] == 'file':
    print(config_path)
    sys.exit(0)
if cmd == 'backend' and len(args) >= 4 and args[1] == 'query':
    q = args[3]
    import re
    pm = re.search(r"'([^']+)' in parents", q)
    p = pm.group(1) if pm else 'root'
    nm = re.search(r"name = '((?:\\.|[^'])*)'", q)
    name = nm.group(1).replace("\\'", "'").replace('\\\\', '\\') if nm else None
    mm = re.search(r"mimeType = '([^']+)'", q)
    mt = mm.group(1) if mm else None
    matches = []
    for f in state['folders']:
        if f['parent'] == p and (name is None or f['name'] == name) and (mt is None or mt == 'application/vnd.google-apps.folder'):
            matches.append({'id': f['id'], 'name': f['name'], 'mimeType':'application/vnd.google-apps.folder', 'size':'0', 'parents':[p]})
    for f in state['files']:
        if f['parent'] == p and (name is None or f['name'] == name) and (mt is None or mt == 'application/octet-stream'):
            matches.append({'id': f['id'], 'name': f['name'], 'mimeType':'application/octet-stream', 'size':str(f['size']), 'md5Checksum':f['md5'], 'parents':[p]})
    output(matches); sys.exit(0)
if cmd == 'lsjson':
    dirs_only = '--dirs-only' in args
    files_only = '--files-only' in args
    p = parent_id()
    items = []
    if not files_only:
        for f in state['folders']:
            if f['parent'] == p:
                items.append({'ID':f['id'], 'Name':f['name'], 'IsDir':True, 'Size':-1})
    if not dirs_only:
        for f in state['files']:
            if f['parent'] == p:
                items.append({'ID':f['id'], 'Name':f['name'], 'IsDir':False, 'Size':f['size'], 'Hashes':{'MD5':f['md5']}})
    output(items); sys.exit(0)
if cmd == 'mkdir' and len(args) >= 2:
    dest=args[-1]
    name=dest.split(':',1)[1]
    state['folders'].append({'id':'folder-'+str(len(state['folders'])+1), 'name':name, 'parent':parent_id()})
    save(); sys.exit(0)
if cmd == 'rcat' and len(args) >= 2:
    dest=args[-1]
    name=dest.split(':',1)[1]
    data=sys.stdin.buffer.read()
    state['files'].append({'id':'file-'+str(len(state['files'])+1), 'name':name, 'parent':parent_id(), 'size':len(data), 'md5':hashlib.md5(data).hexdigest()})
    save(); sys.exit(0)
print('Unsupported fake rclone command: '+repr(args), file=sys.stderr)
sys.exit(2)
