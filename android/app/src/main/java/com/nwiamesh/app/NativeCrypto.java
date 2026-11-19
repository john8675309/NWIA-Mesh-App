package com.nwiamesh.app;
/** Unchanged upstream MeshCore identity primitives, executed on Android only. */
public final class NativeCrypto {
    static {System.loadLibrary("nwia_crypto");}
    public static native byte[] keypair(byte[] seed);
    public static native byte[] sharedSecret(byte[] privateKey,byte[] publicKey);
    public static native byte[] sign(byte[] privateKey,byte[] publicKey,byte[] message);
    public static native boolean verify(byte[] publicKey,byte[] message,byte[] signature);
    private NativeCrypto(){}
}
