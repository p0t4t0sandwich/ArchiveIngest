package dev.neuralnexus.archiveingest.data.mca;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

public record ArchiveInfo(
        long archivedAt,
        @Nullable String archivedBy,
        @Nullable String notes,
        Collection<Source> sources
) {
    public record Source(
            @NonNull String rel,
            @NonNull String href
    ) {}
}
