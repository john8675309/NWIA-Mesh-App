"""Keyless NWIA inbox. Run behind TLS; credentials/configuration are external."""
import asyncio
import base64
import hashlib
import json
import re
import queue
import secrets
import sqlite3
import ssl
import struct
import time
from collections import defaultdict, deque
from pathlib import Path

from aiohttp import web
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

GROUP = re.compile(r'[A-Za-z0-9_-]{1,40}\Z')
HEX12 = re.compile(r'[a-f0-9]{12}\Z')


def payload(raw):
    if not 3 <= len(raw) <= 255 or raw[0] & 192:
        raise ValueError('packet bounds')
    pos = 5 if raw[0] & 3 in (0, 3) else 1
    if pos >= len(raw):
        raise ValueError('route')
    width, hops = (raw[pos] >> 6) + 1, raw[pos] & 63
    end = pos + 1 + width * hops
    if width > 3 or width * hops > 64 or end >= len(raw):
        raise ValueError('path')
    return raw[0] >> 2 & 15, raw[end:]


def envelope(topic, data, now):
    if not 23 <= len(data) <= 275 or data[:4] != b'NWI2':
        raise ValueError('envelope')
    parts = topic.split('/')
    if len(parts) != 5 or parts[:2] != ['nwiamesh', 'v2'] or parts[3] != 'packets' or not GROUP.fullmatch(parts[2]):
        raise ValueError('topic')
    origin = data[4:10].hex()
    created, expires, length = struct.unpack('>IIH', data[10:20])
    if parts[4] != origin or created > now + 2 or expires <= now or expires - created != 60 or length != len(data) - 20:
        raise ValueError('binding/expiry')
    raw = data[20:]
    kind, body = payload(raw)
    return parts[2], origin, raw, kind, body, hashlib.sha256(bytes([kind]) + body).hexdigest()


def canonical(method, path, group, origin, public, stamp, nonce, body, audience='https://broker.example.invalid'):
    return ('NWIA-INBOX-1\n' + '\n'.join([audience, method, path, group, origin, public, str(stamp), nonce, hashlib.sha256(body).hexdigest()])).encode()


def verify(method, path, headers, body, now, audience='https://broker.example.invalid'):
    if headers.get('X-NWIA-Audience')!=audience:raise ValueError('audience')
    group, origin, public = [headers.get('X-NWIA-' + k, '') for k in ['Group', 'Origin', 'Public']]
    stamp, nonce, signature = [headers.get('X-NWIA-' + k, '') for k in ['Time', 'Nonce', 'Signature']]
    if not GROUP.fullmatch(group) or not HEX12.fullmatch(origin) or not re.fullmatch('[a-f0-9]{64}', public) or not re.fullmatch('[a-f0-9]{32}', nonce):
        raise ValueError('identity')
    if not stamp.isdecimal() or abs(now - int(stamp)) > 60 or not re.fullmatch('[a-f0-9]{128}', signature):
        raise ValueError('freshness/signature')
    Ed25519PublicKey.from_public_bytes(bytes.fromhex(public)).verify(bytes.fromhex(signature), canonical(method, path, group, origin, public, int(stamp), nonce, body, audience))
    return group, origin, public, nonce


class Store:
    def __init__(self, path, retention=72*3600, capacity=1024):
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.retention, self.capacity = retention, capacity
        self.db.executescript('''
          PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;
          CREATE TABLE IF NOT EXISTS clients(g TEXT,o TEXT,p TEXT,token TEXT,channels TEXT,updated INTEGER,enabled INTEGER,rev INTEGER DEFAULT 0,dropped INTEGER DEFAULT 0,served INTEGER DEFAULT 0,PRIMARY KEY(g,o));
          CREATE TABLE IF NOT EXISTS items(seq INTEGER PRIMARY KEY AUTOINCREMENT,g TEXT,o TEXT,id TEXT,raw BLOB,at INTEGER,UNIQUE(g,o,id));
          CREATE INDEX IF NOT EXISTS items_client ON items(g,o,seq);
          CREATE TABLE IF NOT EXISTS recent(g TEXT,id TEXT,at INTEGER,PRIMARY KEY(g,id));
          CREATE TABLE IF NOT EXISTS nonces(g TEXT,o TEXT,nonce TEXT,at INTEGER,PRIMARY KEY(g,o,nonce));
        ''')
        if "rev" not in {r[1] for r in self.db.execute("PRAGMA table_info(clients)")}:self.db.execute("ALTER TABLE clients ADD COLUMN rev INTEGER DEFAULT 0")
        self.db.commit()

    def nonce(self, g, o, nonce, now):
        with self.db:
            self.db.execute('DELETE FROM nonces WHERE at<?', (now-120,))
            if self.db.execute('SELECT COUNT(*) FROM nonces').fetchone()[0] >= 10000:
                raise ValueError('nonce capacity')
            self.db.execute('INSERT INTO nonces VALUES(?,?,?,?)', (g,o,nonce,now))

    def client(self,g,o):
        return self.db.execute('SELECT * FROM clients WHERE g=? AND o=?',(g,o)).fetchone()

    def register(self,g,o,p,body,now):
        token, channels, enabled = body.get('token',''), body.get('channels',[]), body.get('enabled')
        if not isinstance(enabled,bool) or not isinstance(token,str) or (enabled and not re.fullmatch('[A-Za-z0-9:_-]{20,4096}',token)):
            raise ValueError('registration')
        if not isinstance(channels,list) or len(channels)>16 or any(not isinstance(c,str) or not re.fullmatch('[a-f0-9]{2}',c) for c in channels):
            raise ValueError('channels')
        revision=body.get('revision',0)
        if not isinstance(revision,int) or isinstance(revision,bool) or not 0<=revision<=9007199254740991:raise ValueError('revision')
        old=self.client(g,o)
        if old and revision<old['rev']:raise ValueError('stale configuration')
        if old and old['p']!=p:
            raise PermissionError('identity already bound')
        if not old and self.db.execute('SELECT COUNT(*) FROM clients').fetchone()[0]>=256:
            raise ValueError('client capacity')
        with self.db:
            self.db.execute('INSERT INTO clients(g,o,p,token,channels,updated,enabled,rev) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(g,o) DO UPDATE SET token=excluded.token,channels=excluded.channels,updated=excluded.updated,enabled=excluded.enabled,rev=excluded.rev', (g,o,p,token if enabled else '',json.dumps(sorted(set(channels))),now,int(enabled),revision))
            if not enabled:self.db.execute('DELETE FROM items WHERE g=? AND o=?',(g,o))

    def ingest(self,topic,data,now):
        g,origin,raw,kind,body,event=envelope(topic,data,now)
        # Only encrypted chat; do not store public adverts, management, or broadcast ACKs.
        if (kind==2 and len(body)>=20 and (len(body)-4)%16==0) or (kind==5 and len(body)>=19 and (len(body)-3)%16==0):pass
        else:return []
        targets=[]
        with self.db:
            self.db.execute('DELETE FROM recent WHERE at<?',(now-3600,))
            if self.db.execute('SELECT 1 FROM recent WHERE g=? AND id=?',(g,event)).fetchone():return []
            if self.db.execute('SELECT COUNT(*) FROM recent').fetchone()[0]>=65536:return []
            self.db.execute('INSERT INTO recent VALUES(?,?,?)',(g,event,now))
            self.expire(now)
            for c in self.db.execute('SELECT * FROM clients WHERE g=? AND enabled=1 AND updated>?',(g,now-30*86400)).fetchall():
                if c['o']==origin:continue
                match=(kind==2 and bytes.fromhex(c['p'])[0]==body[0]) or (kind==5 and f'{body[0]:02x}' in json.loads(c['channels']))
                if not match:continue
                self.db.execute('INSERT OR IGNORE INTO items(g,o,id,raw,at) VALUES(?,?,?,?,?)',(g,c['o'],event,raw,now))
                count=self.db.execute('SELECT COUNT(*) FROM items WHERE g=? AND o=?',(g,c['o'])).fetchone()[0]
                if count>self.capacity:
                    self.db.execute('DELETE FROM items WHERE seq IN (SELECT seq FROM items WHERE g=? AND o=? ORDER BY seq LIMIT ?)',(g,c['o'],count-self.capacity))
                    self.db.execute('UPDATE clients SET dropped=dropped+? WHERE g=? AND o=?',(count-self.capacity,g,c['o']))
                targets.append((g,c['o'],event))
        return targets

    def expire(self,now):
        for r in self.db.execute('SELECT g,o,COUNT(*) n FROM items WHERE at<? GROUP BY g,o',(now-self.retention,)).fetchall():
            self.db.execute('UPDATE clients SET dropped=dropped+? WHERE g=? AND o=?',(r['n'],r['g'],r['o']))
        self.db.execute('DELETE FROM items WHERE at<?',(now-self.retention,))
        self.db.execute('DELETE FROM items WHERE EXISTS(SELECT 1 FROM clients c WHERE c.g=items.g AND c.o=items.o AND c.updated<?)',(now-30*86400,))

    def read(self,g,o,now):
        with self.db:
            self.expire(now)
            rows=self.db.execute('SELECT * FROM items WHERE g=? AND o=? ORDER BY seq LIMIT 64',(g,o)).fetchall()
            if rows:self.db.execute('UPDATE clients SET served=MAX(served,?) WHERE g=? AND o=?',(rows[-1]['seq'],g,o))
        c=self.client(g,o)
        return {'items':[{'seq':r['seq'],'id':r['id'],'raw':base64.b64encode(r['raw']).decode(),'received':r['at']} for r in rows], 'dropped':c['dropped'],'retention_seconds':self.retention}

    def ack(self,g,o,cursor):
        c=self.client(g,o)
        if not isinstance(cursor,int) or isinstance(cursor,bool) or cursor<0 or cursor>c['served']:raise ValueError('cursor')
        with self.db:self.db.execute('DELETE FROM items WHERE g=? AND o=? AND seq<=?',(g,o,cursor))


def mqtt_auth(config,user,password):
    import paho.mqtt.client as mqtt
    import threading
    ready=threading.Event();accepted=[False]
    c=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id='nwia-auth-'+secrets.token_hex(8),protocol=mqtt.MQTTv311)
    def connect(client,userdata,flags,reason,properties):accepted[0]=not reason.is_failure;ready.set()
    c.on_connect=connect;c.username_pw_set(user,password);c.tls_set();c.connect_timeout=5
    try:
        c.connect(config['mqtt_host'],config.get('mqtt_port',8883),15);c.loop_start();ready.wait(6)
        return accepted[0]
    except Exception:return False
    finally:
        try:c.disconnect();c.loop_stop()
        except Exception:pass


class Relay:
    def __init__(self,config,store):
        self.config,self.store=config,store
        approved={(a['group'],a['origin']) for a in config['accounts'].values()}
        with self.store.db:
            for c in self.store.db.execute('SELECT g,o FROM clients').fetchall():
                if (c['g'],c['o']) not in approved:self.store.db.execute('UPDATE clients SET enabled=0,token=? WHERE g=? AND o=?',('',c['g'],c['o']))
        self.mqtt_ready=False;self.rates=defaultdict(deque);self.push_pending={};self.credentials=None;self.ingress=queue.Queue(maxsize=1024)

    async def push(self,g,o):
        # Coalesce bursts, retry boundedly; the durable inbox is the delivery source.
        if (g,o) in self.push_pending:
            self.push_pending[(g,o)]=True;return
        self.push_pending[(g,o)]=False
        try:
            await asyncio.sleep(1)
            for attempt in range(3):
                c=self.store.client(g,o)
                if not c or not c['enabled'] or not c['token']:return
                result=await asyncio.to_thread(self.send_fcm,c['token'])
                if result=='invalid':
                    with self.store.db:self.store.db.execute('UPDATE clients SET token=? WHERE g=? AND o=?',('',g,o))
                    return
                if result=='ok':return
                await asyncio.sleep(2**attempt)
        finally:
            dirty=self.push_pending.pop((g,o),False)
            if dirty:asyncio.create_task(self.push(g,o))

    def send_fcm(self,token):
        import google.auth.transport.requests
        from google.oauth2 import service_account
        import requests
        # Called sequentially through a lock: OAuth refresh is not concurrent.
        with self.fcm_lock:
            try:
                if self.credentials is None:self.credentials=service_account.Credentials.from_service_account_file(self.config['firebase_credentials'],scopes=['https://www.googleapis.com/auth/firebase.messaging'])
                if not self.credentials.valid:self.credentials.refresh(google.auth.transport.requests.Request())
                r=requests.post('https://fcm.googleapis.com/v1/projects/'+self.config['firebase_project']+'/messages:send',headers={'Authorization':'Bearer '+self.credentials.token},json={'message':{'token':token,'data':{'kind':'inbox'},'android':{'priority':'high','ttl':'86400s','collapse_key':'nwia-inbox'}}},timeout=(5,10))
                if r.status_code==200:return 'ok'
                if r.status_code==404 and 'UNREGISTERED' in r.text:return 'invalid'
            except Exception:pass
            return 'retry'

    async def ingest(self,topic,data):
        try:
            for g,o,event in self.store.ingest(topic,data,int(time.time())):asyncio.create_task(self.push(g,o))
        except (ValueError,sqlite3.Error):pass

    async def handler(self,request):
        now=int(time.time())
        if request.path=='/health':return web.json_response({'ok':True,'mqtt':self.mqtt_ready})
        ip=request.remote or 'unknown'
        if ip not in self.rates and len(self.rates)>=4096:
            self.rates=defaultdict(deque,{k:v for k,v in self.rates.items() if v and v[-1]>=now-60})
            if len(self.rates)>=4096:raise web.HTTPTooManyRequests()
        q=self.rates[ip]
        while q and q[0]<now-60:q.popleft()
        if len(q)>=120:raise web.HTTPTooManyRequests()
        if len(self.rates)>4096:self.rates={k:v for k,v in self.rates.items() if v and v[-1]>=now-60};self.rates=defaultdict(deque,self.rates)
        q.append(now)
        try:
            body=await request.read()
            g,o,p,nonce=verify(request.method,request.path_qs,request.headers,body,now,self.config.get('public_url','https://'+self.config.get('mqtt_host','broker.example.invalid')))
            if request.method=='POST' and request.path=='/v1/register':
                auth=base64.b64decode(request.headers.get('Authorization','').removeprefix('Basic '),validate=True).decode().split(':',1)
                if len(auth)!=2 or self.config['accounts'].get(auth[0])!={'group':g,'origin':o}:raise PermissionError()
                if not await asyncio.to_thread(mqtt_auth,self.config,*auth):raise PermissionError()
                self.store.nonce(g,o,nonce,now)
                self.store.register(g,o,p,json.loads(body),now)
                return web.json_response({'registered':True})
            if {'group':g,'origin':o} not in self.config['accounts'].values():raise PermissionError()
            c=self.store.client(g,o)
            if not c or c['p']!=p or not c['enabled']:raise PermissionError()
            self.store.nonce(g,o,nonce,now)
            if request.method=='GET' and request.path_qs=='/v1/inbox':return web.json_response(self.store.read(g,o,now),headers={'Cache-Control':'no-store'})
            if request.method=='POST' and request.path=='/v1/ack':
                self.store.ack(g,o,json.loads(body)['cursor']);return web.json_response({'ok':True})
            raise web.HTTPNotFound()
        except web.HTTPException:raise
        except PermissionError:raise web.HTTPUnauthorized() from None
        except Exception:raise web.HTTPBadRequest() from None

    def start_mqtt(self,loop):
        import paho.mqtt.client as mqtt
        c=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id='nwia-inbox-relay',protocol=mqtt.MQTTv311)
        c.username_pw_set(self.config['mqtt_user'],self.config['mqtt_password']);c.tls_set();c.reconnect_delay_set(1,30)
        def connected(client,userdata,flags,reason,properties):
            if not reason.is_failure:
                client.subscribe([(f'nwiamesh/v2/{g}/packets/+',0) for g in sorted({a['group'] for a in self.config['accounts'].values()})])
        def subscribed(client,userdata,mid,codes,properties):self.mqtt_ready=bool(codes) and not any(code.is_failure for code in codes)
        c.on_connect=connected;c.on_subscribe=subscribed
        c.on_disconnect=lambda *args:setattr(self,'mqtt_ready',False)
        def message(client,userdata,msg):
            if msg.retain:return
            try:self.ingress.put_nowait((msg.topic,bytes(msg.payload)))
            except queue.Full:pass
        c.on_message=message
        c.connect_async(self.config['mqtt_host'],self.config.get('mqtt_port',8883),30);c.loop_start();self.mqtt=c


async def main(config):
    import threading
    relay=Relay(config,Store(config['database']));relay.fcm_lock=threading.Lock()
    app=web.Application(client_max_size=8192);app.router.add_route('*','/{tail:.*}',relay.handler)
    runner=web.AppRunner(app,access_log=None);await runner.setup()
    tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.minimum_version=ssl.TLSVersion.TLSv1_2;tls.load_cert_chain(config['tls_cert'],config['tls_key'])
    for host in config.get('listen',['0.0.0.0','::']):
        await web.TCPSite(runner,host,config.get('port',443),ssl_context=tls).start()
    relay.start_mqtt(asyncio.get_running_loop())
    async def consume():
        while True:
            for unused in range(32):
                try:topic,data=relay.ingress.get_nowait()
                except queue.Empty:break
                await relay.ingest(topic,data)
            await asyncio.sleep(0.01)
    consumer=asyncio.create_task(consume())
    try:
        while True:
            await asyncio.sleep(60)
            with relay.store.db:relay.store.expire(int(time.time()))
    finally:consumer.cancel();relay.mqtt.disconnect();relay.mqtt.loop_stop();await runner.cleanup()


if __name__=='__main__':
    import argparse,os
    parser=argparse.ArgumentParser();parser.add_argument('config',type=Path);args=parser.parse_args();os.umask(0o077)
    asyncio.run(main(json.loads(args.config.read_text())))
