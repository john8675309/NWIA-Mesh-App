import base64
import json
import secrets
import struct
import unittest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from relay import Store,canonical,envelope,payload,verify

NOW=1800000000
ORIGIN='020000000001'
KEY=Ed25519PrivateKey.from_private_bytes(bytes(range(32)))
PUB=KEY.public_key().public_bytes(serialization.Encoding.Raw,serialization.PublicFormat.Raw).hex()
RAW=bytes.fromhex('15005d8e133860a047ee3f8f4fb1e3a42b9f57e93a')

def wire(raw=RAW,origin=ORIGIN,at=NOW):
 return b'NWI2'+bytes.fromhex(origin)+struct.pack('>IIH',at,at+60,len(raw))+raw

def headers(method='GET',path='/v1/inbox',body=b'',origin=ORIGIN):
 n=secrets.token_hex(16);h={'X-NWIA-Audience':'https://broker.example.invalid','X-NWIA-Group':'pilot','X-NWIA-Origin':origin,'X-NWIA-Public':PUB,'X-NWIA-Time':str(NOW),'X-NWIA-Nonce':n}
 h['X-NWIA-Signature']=KEY.sign(canonical(method,path,'pilot',origin,PUB,NOW,n,body)).hex();return h

class RelayTest(unittest.TestCase):
 def setUp(self):self.s=Store(':memory:',retention=100,capacity=2)
 def register(self,o=ORIGIN,channels=['5d']):self.s.register('pilot',o,PUB,{'token':'a'*40,'channels':channels,'enabled':True},NOW)
 def test_native_vector_topic_and_expiry(self):
  g,o,raw,kind,b,event=envelope('nwiamesh/v2/pilot/packets/'+ORIGIN,wire(),NOW)
  self.assertEqual((g,o,kind,raw),('pilot',ORIGIN,5,RAW))
  for topic,frame,now in [('nwiamesh/v2/pilot/packets/020000000002',wire(),NOW),('nwiamesh/v2/pilot/packets/'+ORIGIN,wire(),NOW+60),('nwiamesh/v2/pilot/packets/'+ORIGIN,wire(),NOW-3)]:
   with self.assertRaises(ValueError):envelope(topic,frame,now)
 def test_routing_does_not_change_id(self):
  routed=RAW[:1]+bytes([2,17,34])+RAW[2:]
  t='nwiamesh/v2/pilot/packets/'+ORIGIN
  self.assertEqual(envelope(t,wire(),NOW)[-1],envelope(t,wire(routed),NOW)[-1])
 def test_signature_replay_and_request_binding(self):
  h=headers();self.assertEqual(verify('GET','/v1/inbox',h,b'',NOW)[2],PUB)
  for method,path,body,now in [('POST','/v1/inbox',b'',NOW),('GET','/v1/ack',b'',NOW),('GET','/v1/inbox',b'x',NOW),('GET','/v1/inbox',b'',NOW+61)]:
   with self.assertRaises(Exception):verify(method,path,h,body,now)
  with self.assertRaises(Exception):verify('GET','/v1/inbox',h,b'',NOW,audience='https://other.example.invalid')
  self.s.nonce('pilot',ORIGIN,h['X-NWIA-Nonce'],NOW)
  with self.assertRaises(Exception):self.s.nonce('pilot',ORIGIN,h['X-NWIA-Nonce'],NOW)
 def test_identity_lock(self):
  self.register()
  with self.assertRaises(PermissionError):self.s.register('pilot',ORIGIN,'ff'*32,{'token':'a'*40,'channels':[],'enabled':True},NOW)
 def test_ciphertext_delivery_and_ack_isolation(self):
  self.register();self.register('020000000002')
  targets=self.s.ingest('nwiamesh/v2/pilot/packets/8053e06f0924',wire(origin='8053e06f0924'),NOW)
  self.assertEqual(len(targets),2)
  result=self.s.read('pilot',ORIGIN,NOW);item=result['items'][0]
  self.assertEqual(base64.b64decode(item['raw']),RAW)
  self.s.ack('pilot',ORIGIN,item['seq']);self.assertFalse(self.s.read('pilot',ORIGIN,NOW)['items'])
  self.assertEqual(len(self.s.read('pilot','020000000002',NOW)['items']),1)
  with self.assertRaises(ValueError):self.s.ack('pilot',ORIGIN,item['seq']+100)
 def test_dedup_and_own_origin(self):
  self.register();topic='nwiamesh/v2/pilot/packets/'+ORIGIN
  self.assertEqual(self.s.ingest(topic,wire(),NOW),[])
  self.assertEqual(self.s.ingest(topic,wire(),NOW),[])
 def test_unsubscribed_channel_and_nonchat(self):
  self.register(channels=['01'])
  t='nwiamesh/v2/pilot/packets/8053e06f0924'
  self.assertEqual(self.s.ingest(t,wire(origin='8053e06f0924'),NOW),[])
  self.assertEqual(self.s.ingest(t,wire(bytes([13,0,1,2,3,4]),origin='8053e06f0924'),NOW),[])
 def test_capacity_and_expiry_are_reported(self):
  self.register();t='nwiamesh/v2/pilot/packets/8053e06f0924'
  for i in range(3):
   raw=bytearray(RAW);raw[-1]=i;self.s.ingest(t,wire(bytes(raw),origin='8053e06f0924'),NOW+i)
  r=self.s.read('pilot',ORIGIN,NOW+3);self.assertEqual(len(r['items']),2);self.assertEqual(r['dropped'],1)
  r=self.s.read('pilot',ORIGIN,NOW+200);self.assertEqual(r['items'],[]);self.assertEqual(r['dropped'],3)
 def test_disabled_inbox_erases_records(self):
  self.register();self.s.ingest('nwiamesh/v2/pilot/packets/8053e06f0924',wire(origin='8053e06f0924'),NOW)
  self.s.register('pilot',ORIGIN,PUB,{'enabled':False,'token':'','channels':[]},NOW)
  self.assertFalse(self.s.read('pilot',ORIGIN,NOW)['items'])
 def test_old_worker_cannot_reenable_a_newer_opt_out(self):
  self.register()
  self.s.register('pilot',ORIGIN,PUB,{'enabled':False,'token':'','channels':[],'revision':2},NOW)
  with self.assertRaises(ValueError):self.s.register('pilot',ORIGIN,PUB,{'enabled':True,'token':'a'*40,'channels':[],'revision':1},NOW)
  self.assertFalse(self.s.client('pilot',ORIGIN)['enabled'])
 def test_invalid_path_and_registration(self):
  for raw in [b'',bytes([0,1,2]),bytes([21,255])+bytes(100)]:
   with self.assertRaises(ValueError):payload(raw)
  for body in [{'enabled':True,'token':'a'*40,'channels':['xx']},{'enabled':True,'token':'bad\nvalue','channels':[]}]:
   with self.assertRaises(ValueError):self.s.register('pilot',ORIGIN,PUB,body,NOW)
if __name__=='__main__':unittest.main()
