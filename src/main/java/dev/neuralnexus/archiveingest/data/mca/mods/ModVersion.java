package dev.neuralnexus.archiveingest.data.mca.mods;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

public record ModVersion(
        long id,
        @NonNull String modId,
        Collection<String> names,
        @NonNull String version,
        @Nullable String description,
        @Nullable String license,
        Collection<String> authors,
        Collection<String> contributors,
        Collection<String> credits,
        Collection<ModLoaderMeta> loaders,
        @NonNull Side side
) {}
