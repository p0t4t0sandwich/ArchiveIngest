package dev.neuralnexus.archiveingest.data.mca;

import com.google.gson.annotations.Expose;

import org.jspecify.annotations.NonNull;

public record Hashes(
        @Expose(serialize = false) long size,
        @NonNull String md5,
        @NonNull String sha1,
        @NonNull String sha256,
        @NonNull String sha512
) {
}
