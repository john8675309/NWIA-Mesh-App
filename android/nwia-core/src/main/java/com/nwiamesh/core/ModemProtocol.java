package com.nwiamesh.core;

import java.util.*;
import java.io.ByteArrayOutputStream;
/** NWIA keyless modem v1: 2-byte BE body length, opcode, 4-byte BE request ID, body.
 * Explicit capability handshake distinguishes this from stock MeshCore companions. */
public final class ModemProtocol {
    public static final int HELLO=1, TRANSMIT=2, CAPS=0x81, TX_RESULT=0x82, RECEIVE=0x83;
    public static final int MAX_BODY=260;
    public record Frame(int opcode, long requestId, byte[] data) {
        public Frame { data=data.clone(); }
        @Override public byte[] data() { return data.clone(); }
    }
    public static byte[] encode(Frame f) {
        if(f.data.length>255 || f.opcode<0 || f.opcode>255 || f.requestId<0 || f.requestId>0xffffffffL) throw new IllegalArgumentException();
        int len=5+f.data.length; byte[] b=new byte[len+2]; b[0]=(byte)(len>>8); b[1]=(byte)len; b[2]=(byte)f.opcode;
        for(int i=0;i<4;i++) b[3+i]=(byte)(f.requestId>>(24-8*i));
        System.arraycopy(f.data,0,b,7,f.data.length); return b;
    }
    public static final class Decoder {
        private int length=-1, first=-1;
        private final ByteArrayOutputStream pending=new ByteArrayOutputStream();
        private boolean failed;
        public List<Frame> feed(byte[] chunk) {
            if(failed) throw new IllegalStateException("Disconnect and reset malformed stream");
            List<Frame> frames=new ArrayList<>();
            for(byte item:chunk) {
                int b=item&255;
                if(length<0) {
                    if(first<0) { first=b; continue; }
                    length=(first<<8)|b; first=-1;
                    if(length<5 || length>MAX_BODY) { failed=true; throw new IllegalArgumentException("Frame size"); }
                } else {
                    pending.write(b);
                    if(pending.size()==length) {
                        byte[] body=pending.toByteArray(); long id=0; for(int i=1;i<5;i++) id=(id<<8)|(body[i]&255);
                        frames.add(new Frame(body[0]&255,id,Arrays.copyOfRange(body,5,body.length)));
                        length=-1; pending.reset();
                    }
                }
            }
            return frames;
        }
    }
    public static boolean isKeylessCaps(Frame f) {
        return f.opcode==CAPS && Arrays.equals(f.data,new byte[]{'N','W','A','1',1});
    }
}
