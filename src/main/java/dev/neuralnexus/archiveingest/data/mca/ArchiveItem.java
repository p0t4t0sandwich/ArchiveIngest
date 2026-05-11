package dev.neuralnexus.archiveingest.data.mca;

import org.jspecify.annotations.NonNull;

import java.util.Collection;

public interface ArchiveItem {
    @NonNull String id(); // Snowflake ID
    @NonNull String fileName();
    long size();
    @NonNull Hashes hashes();
    Collection<String> related();
    Collection<Link> links();
    @NonNull ArchiveInfo info();
}
