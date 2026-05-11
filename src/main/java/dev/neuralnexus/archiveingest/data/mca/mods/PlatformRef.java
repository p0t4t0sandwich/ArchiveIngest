package dev.neuralnexus.archiveingest.data.mca.mods;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record PlatformRef(
        @NonNull UploadPlatform platform,
        @NonNull String projectId,
        @Nullable String projectSlug,
        @Nullable String fileId
) {}
