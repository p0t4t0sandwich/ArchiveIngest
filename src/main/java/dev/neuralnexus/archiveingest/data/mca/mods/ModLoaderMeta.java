package dev.neuralnexus.archiveingest.data.mca.mods;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record ModLoaderMeta(
        @NonNull ModLoader loader,
        @Nullable String apiVersion,
        @Nullable String loaderVersion,
        @NonNull String mcVersion,
        @NonNull String metaSource  // which file this came from e.g. "fabric.mod.json"
) {}
