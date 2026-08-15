package io.github.zpkdxgames.plexonpanel.identity;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class KeyCodec {
    private KeyCodec() {
    }

    public static PublicKey decodePublic(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.strip());
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid Ed25519 public key", error);
        }
    }

    public static PrivateKey decodePrivate(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.strip());
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid Ed25519 private key", error);
        }
    }
}
