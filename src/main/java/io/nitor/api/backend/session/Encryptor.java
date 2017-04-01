/**
 * Copyright 2017 Nitor Creations Oy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.nitor.api.backend.session;

import io.vertx.core.json.JsonObject;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static io.nitor.api.backend.session.ByteHelpers.write32;
import static java.lang.System.currentTimeMillis;
import static java.nio.file.Files.isReadable;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Files.write;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.DSYNC;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.util.Arrays.copyOf;
import static java.util.Arrays.copyOfRange;
import static javax.crypto.Cipher.DECRYPT_MODE;
import static javax.crypto.Cipher.ENCRYPT_MODE;

public class Encryptor {
    private static final String CRYPTO_ALGORITHM = "AES_256/GCM/NoPadding";
    private static final int AUTH_TAG_LENGTH = 128;
    private static final int NONCE_LENGTH = 12;
    private static final int AES_KEY_LENGTH = 32;
    private static final int AES_AUTH_LENGTH = 32;

    private static final ThreadLocal<Cipher> CIPHER_POOL;
    public static final SecureRandom RANDOM;
    private static final int NONCE_PART1_RAND;
    private static final int NONCE_PART2_STARTUP;
    private static final AtomicInteger NONCE_PART3_COUNTER = new AtomicInteger(0);

    private final SecretKeySpec secretKeySpec;
    private final byte[] aesAuthData;

    static {
        SecureRandom r = new SecureRandom();
        /*
        try {
            r = SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            r = new SecureRandom();
        }
        */
        try {
            // create instance synchronously for eager failure
            Cipher.getInstance(CRYPTO_ALGORITHM);
        } catch (Exception e) {
            throw new RuntimeException("Encryption algorithm not supported");
        }
        CIPHER_POOL = ThreadLocal.withInitial(() -> { try { return Cipher.getInstance(CRYPTO_ALGORITHM); } catch (Exception ex) { throw new RuntimeException(ex);}});
        RANDOM = r;

        /**
         * FYI: If two different messages with same GCM nonce are ever obtained by one party then they can break the encyrption of all future and past messages.
         * Uniqueness of the GCM nonce in this implementation applies as long as:
         * 1) no two servers that start within the same 100ms window generate the same random 32bit number
         * 2) no JVM encrypts more than 2^32 messages.
         * - note the startup counter wraps around every 10 years, which increases the propability of random number collisions.
         */
        NONCE_PART1_RAND = RANDOM.nextInt();
        NONCE_PART2_STARTUP = (int) (currentTimeMillis() / 100);
    }

    public Encryptor(JsonObject cryptConf) {
        try {
            Path secretFile = Paths.get(cryptConf.getString("secretFile", ".secret"));
            byte[] secret;
            if (isReadable(secretFile)) {
                secret = readAllBytes(secretFile);
                if (secret.length != AES_KEY_LENGTH + AES_AUTH_LENGTH) {
                    throw new RuntimeException("Corrupted secret file '" + secretFile.toAbsolutePath() + "'");
                }
            } else {
                secret = new byte[AES_KEY_LENGTH + AES_AUTH_LENGTH];
                RANDOM.nextBytes(secret);
                write(secretFile, secret, CREATE, TRUNCATE_EXISTING, DSYNC);
            }
            byte[] aesKey = copyOf(secret, AES_KEY_LENGTH);
            byte[] aesAuthData = copyOfRange(secret, AES_KEY_LENGTH, AES_KEY_LENGTH + AES_AUTH_LENGTH);
            if (aesKey.length != AES_KEY_LENGTH || aesAuthData.length != AES_AUTH_LENGTH) {
                throw new RuntimeException("Wrong length of key or auth data");
            }
            this.secretKeySpec = new SecretKeySpec(aesKey, "AES");
            this.aesAuthData = aesAuthData;
        } catch (Exception e) {
            throw new RuntimeException("Could not create cipher", e);
        }
    }

    public byte[] decrypt(byte[] crypted) {
        try {
            GCMParameterSpec paramSpec = new GCMParameterSpec(AUTH_TAG_LENGTH, crypted, 0, NONCE_LENGTH);
            Cipher cipher = CIPHER_POOL.get();
            cipher.init(DECRYPT_MODE, secretKeySpec, paramSpec);
            cipher.updateAAD(aesAuthData);
            byte[] decrypted = cipher.doFinal(crypted, NONCE_LENGTH, crypted.length - NONCE_LENGTH);
            return decrypted;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] encrypt(byte[] data) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            write32(nonce, 0, NONCE_PART1_RAND);
            write32(nonce, 4, NONCE_PART2_STARTUP);
            write32(nonce, 8, NONCE_PART3_COUNTER.incrementAndGet());
            GCMParameterSpec paramSpec = new GCMParameterSpec(AUTH_TAG_LENGTH, nonce);
            Cipher cipher = CIPHER_POOL.get();
            cipher.init(ENCRYPT_MODE, secretKeySpec, paramSpec);
            byte[] encrypted = copyOf(nonce, nonce.length + cipher.getOutputSize(data.length));
            cipher.updateAAD(aesAuthData);
            cipher.doFinal(data, 0, data.length, encrypted, nonce.length);
            return encrypted;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}