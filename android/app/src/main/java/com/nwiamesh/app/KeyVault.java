package com.nwiamesh.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.file.Files;
import java.security.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Mesh keys encrypted with an Android Keystore AES key in no-backup private storage.
 * MeshCore keys must be usable by app crypto; the Keystore key wraps them at rest.
 * No key export, device upload, automatic backup, or secret logging interface. */
public final class KeyVault {
    private static final String ALIAS="nwia.mesh.vault.v1";
    private final File directory;
    public KeyVault(Context context) { directory=context.getNoBackupFilesDir(); }
    private static synchronized javax.crypto.SecretKey wrappingKey() throws GeneralSecurityException, IOException {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(store.containsAlias(ALIAS)) return (javax.crypto.SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setKeySize(256).build());
        return generator.generateKey();
    }
    private AtomicFile file(String name) {
        if(!name.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid key name");
        return new AtomicFile(new File(directory,"key-"+name));
    }
    public synchronized void put(String name,byte[] secret) throws GeneralSecurityException,IOException {
        if(secret.length<1 || secret.length>524288) throw new IllegalArgumentException("Key size");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,wrappingKey());
        cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] encrypted=cipher.doFinal(secret); AtomicFile target=file(name); FileOutputStream out=null;
        try { out=target.startWrite(); out.write(1); out.write(cipher.getIV()); out.write(encrypted); target.finishWrite(out); }
        catch(IOException error) { if(out!=null) target.failWrite(out); throw error; }
        finally {Arrays.fill(encrypted,(byte)0);}
    }
    public synchronized byte[] get(String name) throws GeneralSecurityException,IOException {
        byte[] stored=file(name).readFully();
        try {
            if(stored.length<29 || stored.length>524317 || stored[0]!=1) throw new IOException("Vault format");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,wrappingKey(),new GCMParameterSpec(128,stored,1,12));
            cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return cipher.doFinal(stored,13,stored.length-13);
        } finally {Arrays.fill(stored,(byte)0);}
    }
    public synchronized void remove(String name) {file(name).delete();}
}
