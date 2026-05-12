package dev.neuralnexus.archiveingest.data.mca.mods;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

public record Mod(
        @NonNull String modId,
        @NonNull Collection<String> names,
        @Nullable String description,
        @Nullable String license,
        @NonNull Collection<ModLoader> loaders,
        @NonNull Collection<String> mcVersions,
        @NonNull Collection<Side> sides,
        @NonNull Collection<String> authors,
        @NonNull Collection<String> contributors,
        @NonNull Collection<String> credits
) {}
