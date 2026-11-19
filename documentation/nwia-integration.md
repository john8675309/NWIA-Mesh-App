# NWIA Mesh fork: keyless companion and MQTT

Status: source fork and integration assessment,2026-11-19. The current Flutter
application still uses the upstream stock-companion protocol. The NWIA phone-
owned key model, keyless Wio protocol and MQTT fallback are **not integrated in
this fork yet**. A separate Android pilot has passed the live tests below and
is retained as the implementation/reference checkpoint.

Base inspected: upstream dev at34ea0cb7ca5142bdfc0a85e31fd91543f6392e78.
Original MIT license and copyright remain intact. This is an independent fork;
no upstream endorsement or merged upstream implementation is implied.

## What this base supplies

Contacts, channels, chat history, SQLite-backed native history storage, unread
state, routing views, maps, repeater management, background BLE infrastructure,
local notifications, localization and Android/iOS platform runners. These are
useful app foundations; they are not proof of phone-owned mesh keys or internet
messaging without a radio.

## Important differences from the NWIA requirements

| Area | Current source | Required NWIA behavior |
| --- | --- | --- |
| DM/channel sending | Connector builds stock companion commands with text and device-owned crypto | Phone constructs encrypted native packets; Wio transports opaque packets |
| Identity | exportPrivateKey/importPrivateKey read/write the companion identity | Android-owned identity protected by Keystore; no private-key command to Wio |
| Channel keys | setChannel sends PSK to device; ChannelStore serializes PSK to preferences | Phone-only protected key storage; no PSK bytes sent over BLE |
| No radio | Offline screens are read-only and sending is disabled | MQTT-ready app can send/receive without a companion |
| Retry | Existing radio message retry/path logic | One owner of companion-first, ACK deadline and MQTT fallback; same ciphertext |
| Notifications | Local notification and background BLE services | Separate authenticated encrypted inbox/push relay for internet wakeups |
| Storage identity | Mostly scoped by connected radio public key | Scope by phone's mesh identity across Wio/MQTT transitions |

Source references:

- [Connector](../lib/connector/meshcore_connector.dart): sendFrame, private-key
  import/export, text builders and setChannel are the key boundaries.
- [Protocol builders](../lib/connector/meshcore_protocol.dart): stock commands are
  not the NWIA modem byte-stream protocol.
- [Channel store](../lib/storage/channel_store.dart): PSK serialization must not
  become the NWIA protected-key store unchanged.
- [Key helpers](../lib/utils/keys.dart): already understand MeshCore's expanded
  private-key representation, but stock ownership/import flows still target radio.
- [Offline mode](offline-mode.md): current composer and device-state gates need
  a logical app endpoint, not just a connected-radio boolean.
- [Retry service](../lib/services/message_retry_service.dart): avoid simultaneous
  legacy retries and NWIA immutable-packet MQTT fallback.

## Integration boundary

Create an explicit NWIA endpoint that owns app identity, local contacts/channels,
message encryption/decryption and delivery state. Reuse the existing UI/models
through this endpoint. A keyless Wio connection is one radio transport, and MQTT
is another transport of the same encrypted native packet. A radio connection is
not the user identity and must not select a different chat history.

Use the already-tested Android cryptography/Keystore implementation through a
Flutter platform bridge first. Its native MeshCore compatibility vectors and
physical radio checks are the reference. Porting crypto to Dart later requires
the same byte-for-byte vectors and real-peer receipt tests; matching algorithm
names alone is insufficient for expanded Ed25519 keys/ECDH interoperability.

At the phone-to-Wio write boundary, allow only the NWA1 capability handshake and
opaque native packet transport. **Never forward a legacy private-key, channel
PSK, plaintext-text or credential command into the Wio transport**, even if the
firmware would reject it: the bytes would already have entered device RAM. An
unsupported operation must fail locally. Stock key-provisioning paths must be
inaccessible in NWIA mode, including diagnostics/export and reconnect sync.

Wio firmware is already built, flashed and bench-tested separately. It speaks a
length-prefixed keyless protocol over encrypted Nordic UART, with a distinct
capability handshake. Upstream's172-byte stock companion frames cannot simply
be pointed at it. The radio continues to carry native MeshCore packets, so stock
MeshCore recipients can interoperate.

## Delivery contract to carry over

- App-owned identity and channel keys; Wio holds no message keys, even in RAM.
  BLE bonding keys are separate.
- Prefer a ready companion. For DMs, wait for a matched native recipient receipt.
  Unavailable companion, failed TX, disconnect or ACK timeout falls back once to
  MQTT using the same ciphertext, message identity and original native timestamp.
- For channel chat, successful radio TX ends the attempt. Use MQTT only on
  unavailable/failed radio; there is no channel-recipient ACK to wait for.
- MQTT write completion is not recipient delivery. Native short ACKs are not
  signed read receipts. Handle ordinary, encrypted path-return and multipart ACKs.
- Deduplicate UI by native type plus ciphertext payload, ignoring mutable RF path.
  Preserve native routing/scope/TTL and bounded airtime/queues. No exactly-once
  RF claim. Do not let old retry services re-encrypt/resend in parallel.
- Protect keys with secure storage. Review existing preferences, backups, debug
  frame logs and crash reporting before carrying real keys into this fork.

## Preserve the working identity

The Android pilot currently owns an installed, encrypted identity and a dedicated
MQTT account. Do not uninstall/reset that app, generate a replacement identity,
flash another radio firmware or copy a private key onto Wio during migration.
A future in-place Android update can reuse the existing application ID, signing
certificate, Keystore alias and vault format, but only after explicit migration
validation. The current fork keeps its upstream package/build behavior unchanged;
it has not replaced the tested pilot on the user's phone.

## Existing pilot acceptance to reproduce here

Physical Android17 phone, Wio Tracker L1/L1 Pro, existing full-packet gateway and
stock MeshCore test companion passed:

- Keystore-wrapped identity/settings, tamper/AAD rejection and native keygen,
  ECDH/signature interoperability fixtures.
- Channel send over Wio/RF, receive via Wio with phone MQTT disabled, and send/
  receive over MQTT with no companion connection.
- Signed phone advert, radio-first encrypted DM, matched encrypted native ACK
  and decrypted peer reply.
- MQTT-only encrypted DM and decrypted peer reply. That send did not process a
  matching native ACK; UI kept it unconfirmed. Multipart parsing is unit-tested,
  not asserted as the cause of the missing receipt.
-127,718 deterministic bounds/crypto/framing/transport checks. Not a claim that
  this fork has run those tests or passed NWIA hardware acceptance yet.

Before replacing the pilot, reproduce those tests through this Flutter UI,
including key retention across app updates, no key/PSK BLE writes, no-radio MQTT
composer availability, late-ACK races, dedup and independent RF/internet outages.
Run the repository's pinned-SDK format/analyze/test checks for code changes and
record Android hardware results separately. iOS is a later target.

## Push bolt-on

Use a separate, narrowly authorized service alongside the broker for app identity
registration, encrypted inbox retention, cursored HTTPS sync and FCM token/wakeup
delivery. It must not receive mesh private keys. Push payloads should use opaque
event IDs/generic alerts, with decryption on the phone.

Native RF DM destinations are short hashes, not full public keys. A keyless relay
cannot always identify one unique recipient; handle collisions with bounded
candidate fanout or a separate authenticated app routing envelope. Do not turn
the existing60-second non-retained RF transport into an unlimited offline queue.
No FCM project, production signing key or push/inbox service is configured here.
