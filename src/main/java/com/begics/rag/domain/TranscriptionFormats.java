package com.begics.rag.domain;

import java.util.Locale;
import java.util.Set;

public final class TranscriptionFormats {
  private static final Set<String> EXTENSIONS =
      Set.of("aac", "flac", "m4a", "mp3", "mp4", "ogg", "opus", "wav", "webm", "wma");

  private TranscriptionFormats() {}

  public static boolean supports(String filename) {
    int dot = filename.lastIndexOf('.');
    return dot >= 0 && EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
  }
}
