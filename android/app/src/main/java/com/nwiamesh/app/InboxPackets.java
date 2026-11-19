package com.nwiamesh.app;

import com.nwiamesh.core.ChannelCrypto;
import com.nwiamesh.core.DirectCrypto;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Decrypt retained chat on the phone using its authenticated original capture time.
 * This decoder never transmits. The worker can return a receipt for a fresh DM. */
final class InboxPackets {
    record Decoded(byte[] frame, byte[] receipt, long timestamp) {}
    static Decoded decode(PhoneIdentity identity, JSONArray contacts, JSONArray channels,
                         byte[] raw, long captured) throws Exception {
        int pos = ChannelCrypto.payloadOffset(raw), kind = raw[0] >> 2 & 15;
        if (kind == 2 && raw.length - pos >= 20 && raw[pos] == identity.publicKey()[0]) {
            for (int i = 0; i < contacts.length(); i++) {
                byte[] pub = unhex(contacts.getJSONObject(i).getString("pub"));
                if (pub.length != 32 || pub[0] != raw[pos + 1]) continue;
                byte[] secret = null, plain = null;
                try {
                    secret = identity.secret(pub);
                    plain = DirectCrypto.decrypt(identity.publicKey(), pub, secret, raw);
                    DirectCrypto.Incoming message = DirectCrypto.text(plain, pub, captured);
                    ByteArrayOutputStream frame = new ByteArrayOutputStream();
                    frame.write(new byte[]{16, 0, 0, 0}); frame.write(pub, 0, 6);
                    frame.write(255); frame.write(0); frame.write(plain, 0, 4);
                    frame.write(message.text().getBytes(StandardCharsets.UTF_8)); frame.write(0);
                    return new Decoded(frame.toByteArray(), DirectCrypto.floodAck(message.ack()),Integer.toUnsignedLong(ByteBuffer.wrap(plain,0,4).order(ByteOrder.LITTLE_ENDIAN).getInt()));
                } catch (GeneralSecurityException ignored) {
                } finally {
                    if (secret != null) Arrays.fill(secret, (byte) 0);
                    if (plain != null) Arrays.fill(plain, (byte) 0);
                }
            }
        } else if (kind == 5) {
            for (int i = 0; i < channels.length(); i++) {
                JSONObject c = channels.getJSONObject(i);
                byte[] key = unhex(c.getString("psk"));
                try {
                    ChannelCrypto.Incoming message = ChannelCrypto.decryptMessage(key, raw);
                    if (Math.abs(captured - message.timestamp()) > 300) continue;
                    ByteArrayOutputStream frame = new ByteArrayOutputStream();
                    frame.write(new byte[]{17, 0, 0, 0, (byte) c.getInt("index"), (byte) 255, 0});
                    frame.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) message.timestamp()).array());
                    frame.write(message.text().getBytes(StandardCharsets.UTF_8)); frame.write(0);
                    return new Decoded(frame.toByteArray(), null,message.timestamp());
                } catch (GeneralSecurityException ignored) {
                } finally { Arrays.fill(key, (byte) 0); }
            }
        }
        return null;
    }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    static byte[] unhex(String value) {
        if (!value.matches("(?:[a-fA-F0-9]{2})+")) throw new IllegalArgumentException();
        byte[] out = new byte[value.length()/2];
        for (int i=0; i<out.length; i++) out[i]=(byte) Integer.parseInt(value.substring(i*2,i*2+2),16);
        return out;
    }
}
