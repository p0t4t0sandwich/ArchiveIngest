package dev.neuralnexus.archiveingest.data.mca;

import org.jspecify.annotations.NonNull;

import java.util.List;

public interface ArchiveItem {
    @NonNull String id(); // Snowflake ID
    @NonNull String fileName();
    long size();           // bytes
    @NonNull String md5();
    @NonNull String sha1();
    @NonNull String sha256();
    @NonNull String sha512();
    @NonNull List<String> related();
    @NonNull List<Link> links();
    @NonNull ArchiveInfo info();
}
