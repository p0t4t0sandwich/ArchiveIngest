package dev.neuralnexus.archiveingest.data.mca.mods.forgelike;

import dev.neuralnexus.archiveingest.data.mca.mods.ExtractResult;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;

public final class NeoForgeModExtractor {
    private static final String META_FILE = "META-INF/neoforge.mods.toml";

    private NeoForgeModExtractor() {}

    public static boolean supports(final @NonNull JarFile jar) {
        return jar.getEntry(META_FILE) != null;
    }

    public static @NonNull ExtractResult extract(final long archiveItemId, final @NonNull Path jarPath) throws IOException {
        return ForgeLikeModExtractor.extract(archiveItemId, jarPath, META_FILE);
    }
}
