package dev.neuralnexus.archiveingest.data.mca.mods;

import org.jspecify.annotations.NonNull;

public record Dependency(
        @NonNull String modId,
        @NonNull String versionRange,
        boolean required
) {}
