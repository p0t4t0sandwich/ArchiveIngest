package dev.neuralnexus.archiveingest.data.mca;

import org.jspecify.annotations.NonNull;

public record Link(@NonNull String rel, @NonNull String href) {}
