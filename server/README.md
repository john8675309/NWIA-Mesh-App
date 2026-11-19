# NWIA encrypted inbox and push relay

The relay subscribes to the existing NWI2 MQTT packet stream using a separate
read-only account. It stores encrypted **text-chat packets**, not plaintext or
mesh private/channel keys. HTTPS inbox access is bound to the phone's full mesh
public identity. Firebase carries only `kind=inbox`, a generic wakeup notice;
Android WorkManager fetches packets and decrypts locally.

A phone registers over HTTPS using its existing, separate MQTT credentials and
an Ed25519 signature. The server authenticates those credentials against the TLS
broker and requires an operator-approved username/group/origin binding. The
first registered public key is locked to that binding. Configuration revisions
prevent an older background worker from undoing a newer opt-out. Subsequent inbox reads
and cursor acknowledgements require fresh, nonce-protected signatures bound to the HTTPS server, method, path,
identity and exact request body. There
are no anonymous inbox reads, bearer tokens in URLs, or plaintext MQTT listeners.

Native DM destinations and channel hashes are only one byte. The relay therefore
stores a bounded candidate fanout; only phones with the appropriate keys can
authenticate/decrypt the text. This does not strengthen MeshCore's short MAC or
provide individual sender authentication for shared-key channel chat. Unknown
senders need their full public key in the phone's contact book.

## Bounds and transfer semantics

- Retention: 72 hours; at most 1,024 candidates per registered phone.
- At most 256 registrations; refresh after 12 hours, inactive after 30 days.
- Reads return at most 64 records. The server deletes acknowledged records only
  after the phone has atomically saved their ciphertext in its Keystore-wrapped
  cache. Retention/overflow removals are counted and shown in the app.
- Phone cache: 512 packets, including at most 64 unreadable/unknown-key candidates.
  Unknown candidates are evicted first. A full readable cache stops server ACKs
  until chats are imported, rather than silently discarding readable messages.
- The Flutter importer waits for history writes before committing local cache
  entries. It deduplicates against persisted history using the original sender,
  text and timestamp. History remains subject to the app's existing limits.
- Mutable RF paths are excluded from packet deduplication IDs. Inbox recovery
  never re-encrypts/replays old chat packets onto LoRa. A newly decrypted DM less
  than four minutes old may return its normal native receipt through the phone's
  own MQTT credentials; channel messages have no recipient receipt.
- Push is best-effort. Lost/coalesced FCM notices are recovered by opening the app
  or the periodic WorkManager sync. Android **Force stop** suspends background
  delivery until the app is opened again. Ordinary background/process termination
  is the app-closed test case; no keys or message cache are removed.
- Collection begins after inbox registration. This is not an archive of traffic
  from before a phone opted in.

## Operator deployment

Use a dedicated Firebase service account with only the Firebase Cloud Messaging
API Admin role (`roles/firebasecloudmessaging.admin`). Put its JSON on the broker
privately; do not commit it or put it in the APK. The client Google Services JSON
is a different file and is also excluded from this repository.

On the existing Debian broker, stage `relay.py`, `requirements.txt` (or the tested
`requirements.lock`) and `install.py` together. The installer reads the sender
service-account JSON from stdin. Example with **operator-specific placeholders**:

```sh
cat /root/private-fcm-sender.json | python3 install.py \
  --host broker.example.org --project your-firebase-project \
  --account-file /root/nwia-app-credentials.json
```

The account file is a JSON object keyed by app username, each containing
`username`, `group`, `origin` and its existing broker `password`. Passwords are
not copied into the HTTPS account allowlist; the broker remains the password
authority. Use one credential/origin per phone, never gateway credentials.

The installer preserves existing password/ACL entries and original ownership,
checks readability as the Mosquitto user before reloading, and backs up both
files privately. It adds a read-only relay user, a restricted `nwia-push` systemd
service, HTTPS on port 443 (IPv4/IPv6), and a separate certificate-renewal hook.
The existing hostname certificate must already exist. No DNS change, new
certificate acceptance, broker persistence or retained-message setting is needed.
The service database/configuration and sender key are outside the source tree.

`GET /health` exposes readiness only. No request bodies, credentials, registration
tokens or message contents are written to access logs. Verify external TLS and
rejected unsigned requests, then register a phone and test real app-closed
reception. Broker/relay connection counters alone do not prove phone delivery.

For a replacement identity, explicitly revoke/delete that phone's registration
and encrypted queue as the operator before reusing its account/origin. The server
cannot recover a lost phone's mesh private key. Removing an account binding and
restarting the service revokes its HTTPS access; also revoke its broker password
when retiring the account.

## Checks

```sh
PYTHONPATH=server python3 -m unittest discover -s server/tests -v
python3 -m py_compile server/relay.py server/install.py
```

Tests cover native packet/envelope vectors, topic/expiry/path bounds, signed
request binding, replay rejection, denied unsigned/unregistered/cross-origin
reads, broker-authorized registration, per-phone ACK isolation, duplicate
suppression, overflow/expiry accounting and explicit disable/erase.
