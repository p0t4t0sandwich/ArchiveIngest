package dev.neuralnexus.archiveingest.data.mca;

import org.jspecify.annotations.Nullable;

import java.util.Collection;

public record ArchiveInfo(
        long archivedAt,
        @Nullable String archivedBy,
        Collection<Source> sources
) {}
