package com.nwiamesh.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Event-driven policy. Caller executes Actions then reports asynchronous results.
 * All times are monotonic milliseconds, never wall clock. No transport receives keys. */
public final class DeliveryEngine {
    public enum Kind { DIRECT, CHANNEL }
    public enum ChannelPolicy { ON_RADIO_FAILURE, DELAYED_MQTT }
    public enum State { RADIO_PENDING, RADIO_WAITING_ACK, RADIO_TRANSMITTED, MQTT_PENDING,
        MQTT_SENT, DELIVERED, FAILED }
    public enum Route { COMPANION, MQTT }
    public record Action(String id, Route route, byte[] ciphertext) {
        public Action { ciphertext = ciphertext.clone(); }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
    }
    public record Status(String id, State state, boolean radioAttempted, boolean mqttAttempted) {}
    private static final class Entry {
        final String id; final byte[] payload; final Kind kind;
        State state; long due; boolean radioAttempted, mqttAttempted;
        Entry(String id, byte[] payload, Kind kind) { this.id=id; this.payload=payload.clone(); this.kind=kind; }
    }
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final long txTimeout, ackTimeout, mqttTimeout, channelDelay;
    private final int capacity;
    private final ChannelPolicy channelPolicy;
    public DeliveryEngine(long txTimeout, long ackTimeout, long mqttTimeout,
                          long channelDelay, ChannelPolicy channelPolicy, int capacity) {
        if (txTimeout<=0 || ackTimeout<=0 || mqttTimeout<=0 || channelDelay<=0 || capacity<=0)
            throw new IllegalArgumentException("Positive deadlines and capacity required");
        this.txTimeout=txTimeout; this.ackTimeout=ackTimeout; this.mqttTimeout=mqttTimeout;
        this.channelDelay=channelDelay; this.channelPolicy=Objects.requireNonNull(channelPolicy); this.capacity=capacity;
    }
    public synchronized List<Action> submit(String id, byte[] payload, Kind kind, boolean companionReady, long now) {
        Objects.requireNonNull(id); Objects.requireNonNull(payload); Objects.requireNonNull(kind);
        if (id.trim().isEmpty() || id.length()>128 || payload.length==0 || payload.length>255) throw new IllegalArgumentException("Invalid message");
        Entry old=entries.get(id);
        if (old!=null) {
            if (!MessageDigest.isEqual(payload,old.payload) || kind!=old.kind) throw new IllegalArgumentException("Message ID reused");
            return List.of();
        }
        if (entries.size()>=capacity) throw new IllegalStateException("Outbox full; archive completed entries");
        Entry e=new Entry(id,payload,kind); entries.put(id,e);
        if (companionReady) {
            e.radioAttempted=true; e.state=State.RADIO_PENDING; e.due=now+txTimeout;
            return List.of(new Action(id,Route.COMPANION,e.payload));
        }
        return fallback(e,now);
    }
    public synchronized List<Action> radioResult(String id, boolean transmitted, long now) {
        Entry e=required(id);
        if (e.state!=State.RADIO_PENDING) return List.of(); // stale callbacks must never reverse delivery
        if (!transmitted) return fallback(e,now);
        e.state=e.kind==Kind.DIRECT ? State.RADIO_WAITING_ACK : State.RADIO_TRANSMITTED;
        e.due=now+(e.kind==Kind.DIRECT ? ackTimeout : channelDelay);
        return List.of();
    }
    public synchronized List<Action> companionDisconnected(long now) {
        List<Action> actions=new ArrayList<>();
        for (Entry e:entries.values()) if (e.state==State.RADIO_PENDING || e.state==State.RADIO_WAITING_ACK)
            actions.addAll(fallback(e,now));
        return actions;
    }
    public synchronized void mqttResult(String id, boolean accepted) {
        Entry e=required(id);
        if (e.state==State.MQTT_PENDING) e.state=accepted ? State.MQTT_SENT : State.FAILED;
        // A completed QoS0 write is explicitly NOT broker acknowledgment or recipient delivery; no automatic repeated floods.
    }
    /** Invoke only after protocol receipt matching/authentication, never on a TX/MQTT callback. */
    public synchronized void recipientReceipt(String id) {
        Entry e=required(id);
        if (e.kind!=Kind.DIRECT) throw new IllegalArgumentException("Channel traffic has no recipient ACK");
        e.state=State.DELIVERED;
    }
    public synchronized List<Action> tick(long now) {
        List<Action> actions=new ArrayList<>();
        for (Entry e:entries.values()) {
            if (now<e.due) continue;
            if (e.state==State.RADIO_PENDING || e.state==State.RADIO_WAITING_ACK ||
                (e.state==State.RADIO_TRANSMITTED && channelPolicy==ChannelPolicy.DELAYED_MQTT))
                actions.addAll(fallback(e,now));
            else if (e.state==State.MQTT_PENDING) e.state=State.FAILED;
        }
        return actions;
    }
    private List<Action> fallback(Entry e,long now) {
        if (e.mqttAttempted || e.state==State.DELIVERED) return List.of();
        e.mqttAttempted=true; e.state=State.MQTT_PENDING; e.due=now+mqttTimeout;
        return List.of(new Action(e.id,Route.MQTT,e.payload));
    }
    public synchronized Status status(String id) {
        Entry e=required(id); return new Status(id,e.state,e.radioAttempted,e.mqttAttempted);
    }
    public synchronized boolean archive(String id) {
        Entry e=required(id);
        if (e.state==State.RADIO_PENDING || e.state==State.RADIO_WAITING_ACK || e.state==State.MQTT_PENDING) return false;
        entries.remove(id); return true;
    }
    private Entry required(String id) { Entry e=entries.get(id); if(e==null) throw new IllegalArgumentException("Unknown message"); return e; }
    /** Route-independent UI dedup ID: native payload family plus original ciphertext, NOT RF path. */
    public static String messageId(int type, byte[] encryptedPayload) {
        try { MessageDigest d=MessageDigest.getInstance("SHA-256"); d.update((byte)type); byte[] hash=d.digest(encryptedPayload); StringBuilder hex=new StringBuilder(64); for(byte b:hash) hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return hex.toString(); }
        catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
