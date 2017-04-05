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
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;

import static java.nio.file.Files.*;
import static java.nio.file.StandardOpenOption.*;
import static java.util.Arrays.copyOf;
import static java.util.Arrays.copyOfRange;
import static javax.crypto.Cipher.DECRYPT_MODE;
import static javax.crypto.Cipher.ENCRYPT_MODE;

public class Encryptor {
    private static final String CRYPTO_ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final String MAC_ALGORITHM = "HmacSHA512";
    private static final int MAC_LENGTH = 64;
    private static final int IV_LENGTH = 16;
    private static final int AES_KEY_LENGTH = 32;
    private static final int AES_AUTH_LENGTH = 32;

    private static final ThreadLocal<CryptoState> CRYPTO_POOL = ThreadLocal.withInitial(Encryptor::buildCrypto);

    private final SecretKeySpec symmetricKey;
    private final SecretKeySpec hmacSecret;

    static {
        // create instance synchronously for eager failure
        buildCrypto();
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
                CRYPTO_POOL.get().random.nextBytes(secret);
                write(secretFile, secret, CREATE, TRUNCATE_EXISTING, DSYNC);
            }
            byte[] aesKey = copyOf(secret, AES_KEY_LENGTH);
            byte[] aesAuthData = copyOfRange(secret, AES_KEY_LENGTH, AES_KEY_LENGTH + AES_AUTH_LENGTH);
            if (aesKey.length != AES_KEY_LENGTH || aesAuthData.length != AES_AUTH_LENGTH) {
                throw new RuntimeException("Wrong length of key or auth data");
            }
            this.symmetricKey = new SecretKeySpec(aesKey, "AES");
            this.hmacSecret = new SecretKeySpec(aesAuthData, MAC_ALGORITHM);
        } catch (Exception e) {
            throw new RuntimeException("Could not create cipher", e);
        }
    }

    public byte[] decrypt(byte[] crypted) {
        try {
            CryptoState crypto = CRYPTO_POOL.get();

            crypto.hmac.init(hmacSecret);
            crypto.hmac.update(crypted, MAC_LENGTH, crypted.length - MAC_LENGTH);
            if(!MessageDigest.isEqual(crypto.hmac.doFinal(), copyOf(crypted, MAC_LENGTH))) {
                throw new Exception("Invalid mac");
            }

            crypto.cipher.init(DECRYPT_MODE, symmetricKey, new IvParameterSpec(crypted, MAC_LENGTH, IV_LENGTH));
            return crypto.cipher.doFinal(crypted, MAC_LENGTH + IV_LENGTH, crypted.length - MAC_LENGTH - IV_LENGTH);
        } catch (Exception e) {
            throw new RuntimeException("Decrypt failed"); // drop the root cause
        }
    }

    public byte[] encrypt(byte[] data) {
        try {
            CryptoState crypto = CRYPTO_POOL.get();

            byte[] iv = new byte[IV_LENGTH];
            crypto.random.nextBytes(iv);

            crypto.cipher.init(ENCRYPT_MODE, symmetricKey, new IvParameterSpec(iv));
            byte[] ciptertext = crypto.cipher.doFinal(data);

            crypto.hmac.init(hmacSecret);
            crypto.hmac.update(iv);
            byte[] mac = crypto.hmac.doFinal(ciptertext);

            byte[] encrypted = new byte[mac.length + iv.length + ciptertext.length];
            System.arraycopy(mac, 0, encrypted, 0, mac.length);
            System.arraycopy(iv, 0, encrypted, mac.length, iv.length);
            System.arraycopy(ciptertext, 0, encrypted, mac.length + iv.length, ciptertext.length);
            return encrypted;
        } catch (Exception e) {
            throw new RuntimeException("Encrypt failed"); // drop the root cause
        }
    }

    static CryptoState buildCrypto() {
        try {
            return new CryptoState(
                    Cipher.getInstance(CRYPTO_ALGORITHM),
                    Mac.getInstance(MAC_ALGORITHM),
                    new SecureRandom());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static class CryptoState {
        final Cipher cipher;
        final Mac hmac;
        final SecureRandom random;

        CryptoState(Cipher cipher, Mac hmac, SecureRandom random) {
            this.cipher = cipher;
            this.hmac = hmac;
            this.random = random;
        }
    }
}
