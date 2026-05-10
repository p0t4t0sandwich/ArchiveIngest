package dev.neuralnexus.archiveingest.data.mca;

public record Dependency(
        String modId,
        String versionRange,   // raw as declared
        boolean required
) {}
