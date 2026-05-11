package dev.neuralnexus.archiveingest.data.mca.mods;

import org.jspecify.annotations.NonNull;

import java.util.Collection;

public record LoaderSupport(
        @NonNull ModLoader loader,
        Collection<String> mcVersions, // as declared, e.g. ["1.20.1", "1.20.4"]
        @NonNull String metaSource     // which file this came from e.g. "fabric.mod.json"
) {}
