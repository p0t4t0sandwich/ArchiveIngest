package dev.neuralnexus.archiveingest.data.mca;

import org.jspecify.annotations.Nullable;

public record PlatformRef(
        UploadPlatform platform,
        String projectId,
        @Nullable String projectSlug,
        @Nullable String fileId
) {}
