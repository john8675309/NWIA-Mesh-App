#!/usr/bin/env python3
"""Root installer. Reads a dedicated FCM service-account JSON from stdin.
Run from a directory containing relay.py and requirements.txt; no mesh keys.
"""
import argparse,datetime,json,os,pathlib,pwd,secrets,shutil,subprocess,sys,tempfile
p=argparse.ArgumentParser();p.add_argument('--host',required=True);p.add_argument('--project',required=True);p.add_argument('--account-file',default='/root/nwia-app-credentials.json');a=p.parse_args()
if os.geteuid()!=0:p.error('Run as root')
import re
if not re.fullmatch('[a-zA-Z0-9.-]+',a.host) or not re.fullmatch('[a-z0-9-]+',a.project):p.error('Invalid host/project')
key=json.load(sys.stdin)
if key.get('project_id')!=a.project or key.get('type')!='service_account':p.error('Wrong service-account project')
os.umask(0o077)
subprocess.run(['apt-get','update','-qq'],check=True,stdout=subprocess.DEVNULL)
subprocess.run(['apt-get','install','-y','-qq','python3-venv'],check=True,stdout=subprocess.DEVNULL)
try:account=pwd.getpwnam('nwia-push')
except KeyError:
 subprocess.run(['useradd','--system','--home-dir','/var/lib/nwia-push','--shell','/usr/sbin/nologin','nwia-push'],check=True);account=pwd.getpwnam('nwia-push')
backup=pathlib.Path('/root')/('nwia-push-before-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d%H%M%S'));backup.mkdir(mode=0o700)
ownership={}
for name in ['nwia.acl','nwia.passwd']:
 st=pathlib.Path('/etc/mosquitto/'+name).stat();ownership[name]=(st.st_uid,st.st_gid)
 shutil.copy2('/etc/mosquitto/'+name,backup/name)
(backup/'ownership.json').write_text(json.dumps(ownership))
for path in ['/etc/nwia-push','/opt/nwia-push','/var/lib/nwia-push']:
 pathlib.Path(path).mkdir(exist_ok=True);os.chown(path,0 if path!='/var/lib/nwia-push' else account.pw_uid,account.pw_gid);os.chmod(path,0o750)
for name in ['relay.py','requirements.txt']:shutil.copy2(name,'/opt/nwia-push/'+name);os.chmod('/opt/nwia-push/'+name,0o644)
subprocess.run(['python3','-m','venv','/opt/nwia-push/venv'],check=True)
subprocess.run(['/opt/nwia-push/venv/bin/pip','install','--disable-pip-version-check','-q','-r','/opt/nwia-push/requirements.txt'],check=True)
for base,dirs,files in os.walk('/opt/nwia-push'):
 for name in ['.']+dirs+files:
  path=pathlib.Path(base) if name=='.' else pathlib.Path(base)/name
  os.chown(path,0,account.pw_gid,follow_symlinks=False)
  if not path.is_symlink():os.chmod(path,0o750 if path.is_dir() or path.stat().st_mode&0o100 else 0o640)
def protected(name,data):
 path=pathlib.Path('/etc/nwia-push')/name;path.write_text(data);os.chown(path,0,account.pw_gid);os.chmod(path,0o640)
protected('firebase.json',json.dumps(key));del key
cfgpath=pathlib.Path('/etc/nwia-push/config.json')
if cfgpath.exists():cfg=json.loads(cfgpath.read_text());password=cfg['mqtt_password']
else:password=secrets.token_hex(24);cfg={}
apps=json.loads(pathlib.Path(a.account_file).read_text());bindings=cfg.get('accounts',{})
for user,app in apps.items():
 group,origin=app['group'],app['origin']
 if app['username']!=user or not re.fullmatch('[A-Za-z0-9_-]{1,64}',user) or not re.fullmatch('[A-Za-z0-9_-]{1,40}',group) or not re.fullmatch('[a-f0-9]{12}',origin):p.error('Invalid app-account binding')
 bindings[user]={'group':group,'origin':origin}
cfg.update(public_url="https://"+a.host,mqtt_host=a.host,mqtt_port=8883,mqtt_user='nwia-push-relay',mqtt_password=password,firebase_project=a.project,firebase_credentials='/etc/nwia-push/firebase.json',database='/var/lib/nwia-push/inbox.sqlite',tls_cert='/etc/nwia-push/fullchain.pem',tls_key='/etc/nwia-push/privkey.pem',accounts=bindings)
protected('config.json',json.dumps(cfg,indent=2)+'\n')
# Let mosquitto_passwd hash a root-only temporary file, without password argv.
fd,name=tempfile.mkstemp(prefix='nwia-relay-password-',dir='/root');os.close(fd)
try:
 pathlib.Path(name).write_text('nwia-push-relay:'+password+'\n');subprocess.run(['mosquitto_passwd','-U',name],check=True)
 target=pathlib.Path('/etc/mosquitto/nwia.passwd');old=target.read_text();lines=[x for x in old.splitlines() if not x.startswith('nwia-push-relay:')];target.write_text('\n'.join(lines)+'\n'+pathlib.Path(name).read_text());os.chmod(target,0o640)
finally:pathlib.Path(name).unlink(missing_ok=True)
acl=pathlib.Path('/etc/mosquitto/nwia.acl');text=acl.read_text();begin='# NWIA PUSH RELAY MANAGED BEGIN';end='# NWIA PUSH RELAY MANAGED END'
text=re.sub(re.escape(begin)+r'\n.*?'+re.escape(end)+r'\n?', '',text,flags=re.S).rstrip()+'\n\n'+begin+'\nuser nwia-push-relay\n'
for approved in sorted({v['group'] for v in bindings.values()}):text+='topic read nwiamesh/v2/'+approved+'/packets/+\n'
text+=end+'\n';acl.write_text(text);os.chmod(acl,0o640)
# Preserve original Mosquitto ownership and all existing account entries.
for name in ['nwia.passwd','nwia.acl']:
 os.chown('/etc/mosquitto/'+name,*ownership[name])
hook='''#!/bin/sh
set -eu
[ "${RENEWED_LINEAGE:-}" = "/etc/letsencrypt/live/HOST" ] || exit 0
install -o root -g nwia-push -m 0640 "$RENEWED_LINEAGE/fullchain.pem" /etc/nwia-push/fullchain.pem
install -o root -g nwia-push -m 0640 "$RENEWED_LINEAGE/privkey.pem" /etc/nwia-push/privkey.pem
if systemctl is-active --quiet nwia-push; then systemctl restart nwia-push; fi
'''.replace('HOST',a.host)
h=pathlib.Path('/etc/letsencrypt/renewal-hooks/deploy/nwia-push');h.write_text(hook);h.chmod(0o750)
subprocess.run(['env','RENEWED_LINEAGE=/etc/letsencrypt/live/'+a.host,str(h)],check=True)
unit='''[Unit]
Description=NWIA encrypted inbox and FCM wakeup relay
After=network-online.target mosquitto.service
Wants=network-online.target
[Service]
User=nwia-push
Group=nwia-push
UMask=0077
WorkingDirectory=/opt/nwia-push
ExecStart=/opt/nwia-push/venv/bin/python /opt/nwia-push/relay.py /etc/nwia-push/config.json
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/nwia-push
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
MemoryMax=256M
TasksMax=96
[Install]
WantedBy=multi-user.target
'''
pathlib.Path('/etc/systemd/system/nwia-push.service').write_text(unit)
for name in ['nwia.acl','nwia.passwd']:subprocess.run(['runuser','-u','mosquitto','--','test','-r','/etc/mosquitto/'+name],check=True)
subprocess.run(['systemctl','reload','mosquitto'],check=True)
subprocess.run(['systemctl','daemon-reload'],check=True)
subprocess.run(['systemctl','enable','--now','nwia-push'],check=True)
print('Relay installed; existing broker accounts preserved. Backup:',backup)
