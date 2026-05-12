package dev.neuralnexus.archiveingest.data.mca;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record Source(
        @NonNull String rel,
        @NonNull String href,
        @Nullable String platform,
        @Nullable String projectId,
        @Nullable String projectSlug,
        @Nullable String fileId
) {}
