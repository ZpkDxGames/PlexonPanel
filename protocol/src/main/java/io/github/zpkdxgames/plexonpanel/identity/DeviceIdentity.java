package io.github.zpkdxgames.plexonpanel.identity;

import io.github.zpkdxgames.plexonpanel.util.Hashing;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public record DeviceIdentity(UUID serverId, Instant createdAt, KeyPair keyPair) {
  public DeviceIdentity {
    Objects.requireNonNull(serverId, "serverId");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(keyPair, "keyPair");
  }

  public String publicKeyBase64() {
    return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
  }

  public String fingerprint() {
    byte[] digest = Hashing.sha256(keyPair.getPublic().getEncoded());
    return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest, 0, 12);
  }

  public String signBase64Url(byte[] value) {
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(keyPair.getPrivate());
      signer.update(value);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    } catch (Exception error) {
      throw new IllegalStateException("Unable to sign protocol message", error);
    }
  }

  public static boolean verify(PublicKey key, byte[] value, String signatureBase64Url) {
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(key);
      verifier.update(value);
      return verifier.verify(Base64.getUrlDecoder().decode(signatureBase64Url));
    } catch (Exception error) {
      return false;
    }
  }
}
