package dev.neuralnexus.archiveingest.compression;

import org.jspecify.annotations.NonNull;

public enum CompressionType {
    ZSTD("zstd");

    private final String type;

    CompressionType(final @NonNull String type) {
        this.type = type;
    }

    public @NonNull String getType() {
        return this.type;
    }

    public static CompressionType of(final @NonNull String type) {
        for (final CompressionType ct : CompressionType.values()) {
            if (ct.type.equalsIgnoreCase(type)) {
                return ct;
            }
        }
        throw new IllegalArgumentException("Unsupported compression type: " + type);
    }
}
