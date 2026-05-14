package dev.neuralnexus.archiveingest.data.mca.mods.sponge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.neuralnexus.archiveingest.data.mca.Link;
import dev.neuralnexus.archiveingest.data.mca.mods.Dependency;
import dev.neuralnexus.archiveingest.data.mca.mods.ExtractResult;
import dev.neuralnexus.archiveingest.data.mca.mods.ModArchive;
import dev.neuralnexus.archiveingest.data.mca.mods.ModLoader;
import dev.neuralnexus.archiveingest.data.mca.mods.ModLoaderMeta;
import dev.neuralnexus.archiveingest.data.mca.mods.ModVersion;
import dev.neuralnexus.archiveingest.data.mca.mods.Side;

import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class LegacySpongeModExtractor {
    private static final String META_FILE = "mcmod.info";

    private LegacySpongeModExtractor() {}

    public static boolean supports(final @NonNull JarFile jar) throws IOException {
        final JarEntry entry = (JarEntry) jar.getEntry(META_FILE);
        if (entry == null) return false;

        try (final InputStream in = jar.getInputStream(entry)) {
            final JsonArray root = ModArchive.GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonArray.class);
            if (root == null || root.isEmpty()) return false;
            final JsonObject mod = root.get(0).getAsJsonObject();
            if (!mod.has("dependencies")) return false;
            for (final JsonElement dep : mod.getAsJsonArray("dependencies")) {
                if (dep.getAsString().contains("spongeapi")) return true;
            }
        }
        return false;
    }

    public static @NonNull ExtractResult extract(final long archiveItemId, final @NonNull Path jarPath) throws IOException {
        final JsonArray root;
        try (final JarFile jar = new JarFile(jarPath.toFile())) {
            final JarEntry entry = (JarEntry) jar.getEntry(META_FILE);
            if (entry == null) throw new IOException("No " + META_FILE + " found in jar");
            try (final InputStream in = jar.getInputStream(entry)) {
                root = ModArchive.GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonArray.class);
            }
        }

        if (root == null || root.isEmpty()) throw new IOException("No mod descriptors found in " + META_FILE);
        final JsonObject mod = root.get(0).getAsJsonObject();

        // --- Required fields ---
        final String modId   = requireString(mod, "modid");
        final String version = resolveVersion(mod, jarPath);

        // --- Optional fields ---
        final String displayName = mod.has("name") ? mod.get("name").getAsString() : null;
        final String description = mod.has("description") ? mod.get("description").getAsString() : null;

        // --- Authors ---
        final List<String> authors = new ArrayList<>();
        if (mod.has("authorList")) {
            for (final JsonElement entry : mod.getAsJsonArray("authorList")) {
                authors.add(entry.getAsString());
            }
        }

        // --- Links ---
        final List<Link> links = new ArrayList<>();
        if (mod.has("url")) {
            final String url = mod.get("url").getAsString();
            if (!url.isBlank()) links.add(new Link("homepage", url));
        }

        // --- MC version ---
        final String mcVersion = mod.has("mcversion") && !mod.get("mcversion").getAsString().isBlank()
                ? mod.get("mcversion").getAsString()
                : "unknown";

        // --- Dependencies ---
        String apiVersion = null;
        final List<Dependency> dependencies = new ArrayList<>();
        if (mod.has("dependencies")) {
            for (final JsonElement entry : mod.getAsJsonArray("dependencies")) {
                final String depId = entry.getAsString();
                if (depId.isBlank()) continue;
                if (depId.contains("spongeapi")) {
                    apiVersion = depId;
                } else {
                    dependencies.add(new Dependency(depId, "*", true));
                }
            }
        }

        final ModLoaderMeta loaderMeta = new ModLoaderMeta(
                ModLoader.SPONGE,
                apiVersion,
                null,
                mcVersion,
                META_FILE,
                dependencies
        );

        return new ExtractResult(
                new ModVersion(
                        archiveItemId,
                        modId,
                        displayName != null ? List.of(displayName) : List.of(),
                        version,
                        description,
                        null,
                        authors,
                        List.of(),
                        List.of(),
                        List.of(loaderMeta),
                        Side.SERVER
                ),
                links
        );
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String requireString(final JsonObject obj, final String key) throws IOException {
        if (!obj.has(key) || obj.get(key).getAsString().isBlank())
            throw new IOException("Missing required field: " + key);
        return obj.get(key).getAsString();
    }

    private static String resolveVersion(final JsonObject mod, final Path jarPath) throws IOException {
        final String version = requireString(mod, "version");
        if (!version.equals("${version}")) return version;
        try (final JarFile jar = new JarFile(jarPath.toFile())) {
            final java.util.jar.Manifest manifest = jar.getManifest();
            final String resolved = manifest != null
                    ? manifest.getMainAttributes().getValue("Implementation-Version")
                    : null;
            if (resolved == null) throw new IOException("Could not resolve ${version} from MANIFEST.MF");
            return resolved;
        }
    }
}
