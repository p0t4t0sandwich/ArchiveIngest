package dev.neuralnexus.archiveingest.data.mca.mods;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

public record ModLoaderMeta(
        @NonNull ModLoader loader,
        @Nullable String apiVersion,
        @Nullable String loaderVersion,
        @NonNull String mcVersion,
        @NonNull String metaSource,
        Collection<Dependency> dependencies
) {}
