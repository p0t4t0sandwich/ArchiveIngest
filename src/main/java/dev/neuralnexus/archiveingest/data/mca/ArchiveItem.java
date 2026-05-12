package dev.neuralnexus.archiveingest.data.mca;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

public record ArchiveItem(
        long id, // Snowflake ID
        @NonNull String fileName,
        long size,
        @NonNull String md5,
        @NonNull String sha1,
        @NonNull String sha256,
        @NonNull String sha512,
        Collection<String> related,
        Collection<Link> links,
        long archivedAt,
        @Nullable String archivedBy,
        Collection<Source> sources
) {}
