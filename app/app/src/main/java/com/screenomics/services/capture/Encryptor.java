package com.screenomics.services.capture;


import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

class Encryptor {
    static void encryptFile(byte[] key, String filename, String inPath, String outPath) throws IOException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException {
        FileInputStream fis = new FileInputStream(inPath);
        FileOutputStream fos = new FileOutputStream(outPath);

        // Grab the first 10 chars of the filename
        String fname = filename.substring(10);
        System.out.println("ENCRYPTING WITH FNAME " + fname);
        // Get 8 bytes from an encrypted version of fname, using SHA-256
        byte[] ivBytes = Arrays.copyOfRange(getSHA(fname), 0, 7);

        System.out.println("ENCRYPTING WITH KEY " + toHex(key));
        System.out.println("ENCRYPTING WITH IV " + toHex(ivBytes));

        // Create a secret key spec using the key and AES/GCM/NoPadding algorithm
        SecretKeySpec secretKeySpec = new SecretKeySpec(key,"AES/GCM/NoPadding");
        // Create a GCMParameterSpec of length 16 * 8, with the iv bytes
        GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(16 * 8, ivBytes);
        // Create an AES/GCM/NoPadding cipher
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // Set the cypher to encrypt using the secret key spec and gcm paramter spec
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, gcmParameterSpec);
        // Run the file through the cipher and into a cipher output stream
        CipherOutputStream cos = new CipherOutputStream(fos, cipher);
        int b;
        byte[] d = new byte[8];
        while ((b = fis.read(d)) != -1) {
            cos.write(d, 0, b);
        }
        cos.flush();
        cos.close();
        fis.close();
    }

    static private byte[] getSHA(String input)  throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(input.getBytes(StandardCharsets.UTF_8));
    }

    private static final String HEX_DIGITS = "0123456789abcdef";
    public static String toHex(byte[] data) {
        StringBuffer buf = new StringBuffer();

        for (int i = 0; i != data.length; i++) {
            int v = data[i] & 0xff;

            buf.append(HEX_DIGITS.charAt(v >> 4));
            buf.append(HEX_DIGITS.charAt(v & 0xf));

            buf.append(" ");
        }

        return buf.toString();
    }
}
