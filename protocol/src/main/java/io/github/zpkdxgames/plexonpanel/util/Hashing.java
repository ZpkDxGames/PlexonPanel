package io.github.zpkdxgames.plexonpanel.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {
  private Hashing() {}

  public static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  public static String sha256Hex(String input) {
    return HexFormat.of().formatHex(sha256(input.getBytes(StandardCharsets.UTF_8)));
  }
}
