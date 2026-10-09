#!/usr/bin/env python3
"""Local end-to-end smoke test using fake rclone (not a live Google Drive test)."""
import json, os, socket, subprocess, tempfile, time, urllib.request, urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / 'termux' / 'cloud_bridge_server.py'
FAKE_RCLONE = ROOT / 'tests' / 'fake_rclone.py'

def req(base, token, path, method='GET', body=None, headers=None):
    h = {'Authorization': 'Bearer '+token}
    h.update(headers or {})
    data = body
    r = urllib.request.Request(base+path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()

def main():
    with tempfile.TemporaryDirectory(prefix='mibox-cloud-test-') as td:
        d=Path(td); app=d/'cloud-bridge'; (app/'config').mkdir(parents=True); (app/'logs').mkdir()
        cfg=d/'rclone.conf'; cfg.write_text('[gdrive]\ntype = drive\n', encoding='utf-8')
        token='TEST_TOKEN_ABCDEFGHIJKLMNOPQRSTUVWXYZ_123456789'
        with socket.socket() as s:
            s.bind(('127.0.0.1',0)); port=s.getsockname()[1]
        (app/'config'/'settings.json').write_text(json.dumps({
            'token':token,'remote':'gdrive','port':port,'rclone_config':str(cfg),
            'min_free_bytes':1,'max_upload_bytes':0,'allow_private_lan_for_testing':False
        }), encoding='utf-8')
        env=os.environ.copy()
        env['MIBOX_CLOUD_HOME']=str(app)
        env['FAKE_RCLONE_STATE']=str(d/'drive-state.json')
        env['FAKE_RCLONE_CONFIG']=str(cfg)
        env['PATH']=str(FAKE_RCLONE.parent)+os.pathsep+env.get('PATH','')
        # The test double is named fake_rclone.py; expose it as `rclone` via a tiny wrapper.
        fakebin=d/'bin'; fakebin.mkdir(); wrapper=fakebin/'rclone'
        wrapper.write_text('#!/bin/sh\nexec "'+str(FAKE_RCLONE)+'" "$@"\n', encoding='utf-8'); wrapper.chmod(0o755)
        env['PATH']=str(fakebin)+os.pathsep+env['PATH']
        proc=subprocess.Popen(['python3',str(SERVER)],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        base=f'http://127.0.0.1:{port}'
        try:
            for _ in range(50):
                try:
                    with urllib.request.urlopen(base+'/api/ping', timeout=1) as r:
                        if r.status==200: break
                except Exception: time.sleep(0.1)
            status,payload=req(base,token,'/api/health')
            assert status==200, (status,payload)
            status,payload=req(base,'WRONG_TOKEN','/api/health')
            assert status==401, (status,payload)
            status,payload=req(base,token,'/api/folders?parent=root')
            assert status==200 and json.loads(payload)['folders']==[], (status,payload)
            status,payload=req(base,token,'/api/folders', 'POST', json.dumps({'parent_id':'root','name':'Test Folder'}).encode(), {'Content-Type':'application/json'})
            assert status==201, (status,payload)
            folder=json.loads(payload)['folder']; fid=folder['id']
            status,payload=req(base,token,'/api/folders', 'POST', json.dumps({'parent_id':'root','name':'Test Folder'}).encode(), {'Content-Type':'application/json'})
            assert status==409, (status,payload)
            data=b'Universal MiBox Cloud test data\x00\x01\x02'
            url='/api/upload?parent_id='+fid+'&name=sample.bin&size='+str(len(data))
            status,payload=req(base,token,url,'POST',data,{'Content-Type':'application/octet-stream','Content-Length':str(len(data))})
            assert status==200, (status,payload)
            result=json.loads(payload)
            assert result['ok'] and result['verified'] and result['size']==len(data), result
            status,payload=req(base,token,url,'POST',data,{'Content-Type':'application/octet-stream','Content-Length':str(len(data))})
            assert status==409, (status,payload)
            print('PASS: valid/invalid token auth, folder listing/create/duplicate protection, streamed upload verification, and file duplicate protection (fake rclone).')
        finally:
            proc.terminate()
            try: proc.wait(timeout=5)
            except subprocess.TimeoutExpired: proc.kill()
            if proc.returncode not in (0, -15, 143):
                out,err=proc.communicate(timeout=1)
                print(out); print(err)
                raise SystemExit('server process returned '+str(proc.returncode))

if __name__=='__main__': main()
