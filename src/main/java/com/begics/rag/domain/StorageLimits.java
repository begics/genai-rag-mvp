package com.begics.rag.domain;

public final class StorageLimits {
  public static final long USER_LIMIT_BYTES = 1_000_000_000L;

  private StorageLimits() {}

  public static String formatBytes(long bytes) {
    if (bytes >= 1_000_000_000L) {
      return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0);
    }
    if (bytes >= 1_000_000L) {
      return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
    }
    return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1_000.0);
  }
}
