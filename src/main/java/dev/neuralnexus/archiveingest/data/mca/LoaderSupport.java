package dev.neuralnexus.archiveingest.data.mca;

import java.util.List;

public record LoaderSupport(
        ModLoader loader,
        List<String> mcVersions,   // as declared, e.g. ["1.20.1", "1.20.4"]
        String metaSource          // which file this came from e.g. "fabric.mod.json"
) {}
