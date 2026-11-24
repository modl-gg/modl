package io.github._4drian3d.signedvelocity.shared;

public final class SignedConstants {
  public static final String SIGNED_NAMESPACE = "signedvelocity";
  public static final String SIGNED_CHANNEL = "main";
  public static final String SIGNED_PLUGIN_CHANNEL = SIGNED_NAMESPACE + ':' + SIGNED_CHANNEL;
  public static final int MAX_DENIED_TEXT_LENGTH = 256;

  public static String deniedTextExcerpt(final String text) {
    return text.length() > MAX_DENIED_TEXT_LENGTH ? text.substring(0, MAX_DENIED_TEXT_LENGTH) : text;
  }
}
