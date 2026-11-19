import base64,json,secrets,time,unittest
from unittest.mock import patch
from aiohttp import web
from aiohttp.test_utils import TestClient,TestServer
from test_relay import KEY,PUB,ORIGIN
from relay import Relay,Store,canonical

class HttpTest(unittest.IsolatedAsyncioTestCase):
 async def asyncSetUp(self):
  self.relay=Relay({'accounts':{'phone':{'group':'pilot','origin':ORIGIN}}},Store(':memory:'))
  app=web.Application(client_max_size=8192);app.router.add_route('*','/{tail:.*}',self.relay.handler)
  self.client=TestClient(TestServer(app));await self.client.start_server()
 async def asyncTearDown(self):await self.client.close()
 def h(self,method,path,body=b'',origin=ORIGIN):
  now=int(time.time());n=secrets.token_hex(16)
  h={'X-NWIA-Audience':'https://broker.example.invalid','X-NWIA-Group':'pilot','X-NWIA-Origin':origin,'X-NWIA-Public':PUB,'X-NWIA-Time':str(now),'X-NWIA-Nonce':n}
  h['X-NWIA-Signature']=KEY.sign(canonical(method,path,'pilot',origin,PUB,now,n,body)).hex();return h
 async def register(self):
  body=json.dumps({'enabled':True,'token':'a'*40,'channels':[]}).encode();h=self.h('POST','/v1/register',body);h['Authorization']='Basic '+base64.b64encode(b'phone:secret').decode()
  with patch('relay.mqtt_auth',return_value=True):r=await self.client.post('/v1/register',headers=h,data=body)
  self.assertEqual(r.status,200)
 async def test_unsigned_and_unregistered_denied(self):
  r=await self.client.get('/v1/inbox');self.assertEqual(r.status,400)
  r=await self.client.get('/v1/inbox',headers=self.h('GET','/v1/inbox'));self.assertEqual(r.status,401)
  self.assertEqual(self.relay.store.db.execute('SELECT COUNT(*) FROM nonces').fetchone()[0],0)
 async def test_registration_requires_broker_auth(self):
  body=json.dumps({'enabled':True,'token':'a'*40,'channels':[]}).encode();h=self.h('POST','/v1/register',body);h['Authorization']='Basic '+base64.b64encode(b'phone:bad').decode()
  with patch('relay.mqtt_auth',return_value=False):r=await self.client.post('/v1/register',headers=h,data=body)
  self.assertEqual(r.status,401);self.assertIsNone(self.relay.store.client('pilot',ORIGIN))
 async def test_registered_read_replay_and_cross_origin(self):
  await self.register();h=self.h('GET','/v1/inbox');r=await self.client.get('/v1/inbox',headers=h);self.assertEqual(r.status,200)
  r=await self.client.get('/v1/inbox',headers=h);self.assertEqual(r.status,400)
  r=await self.client.get('/v1/inbox',headers=self.h('GET','/v1/inbox',origin='020000000002'));self.assertEqual(r.status,401)
 async def test_invalid_ack_and_oversized_body(self):
  await self.register();body=b'{"cursor":999}';r=await self.client.post('/v1/ack',headers=self.h('POST','/v1/ack',body),data=body);self.assertEqual(r.status,400)
  r=await self.client.post('/v1/register',data=b'x'*8193);self.assertEqual(r.status,413)
