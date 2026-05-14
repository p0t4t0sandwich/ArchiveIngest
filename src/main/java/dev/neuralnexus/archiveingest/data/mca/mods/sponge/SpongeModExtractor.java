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

public final class SpongeModExtractor {
    private static final String META_FILE = "META-INF/sponge_plugins.json";

    private SpongeModExtractor() {}

    public static boolean supports(final @NonNull JarFile jar) {
        return jar.getEntry(META_FILE) != null;
    }

    public static @NonNull ExtractResult extract(final long archiveItemId, final @NonNull Path jarPath) throws IOException {
        final JsonObject plugin;
        try (final JarFile jar = new JarFile(jarPath.toFile())) {
            final JarEntry entry = (JarEntry) jar.getEntry(META_FILE);
            if (entry == null) throw new IOException("No " + META_FILE + " found in jar");
            try (final InputStream in = jar.getInputStream(entry)) {
                final JsonObject root = ModArchive.GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                final JsonArray plugins = root.getAsJsonArray("plugins");
                if (plugins == null || plugins.isEmpty()) throw new IOException("No plugin descriptors found in " + META_FILE);
                plugin = plugins.get(0).getAsJsonObject();
            }
        }

        // --- Required fields ---
        final String modId   = requireString(plugin, "id");
        final String version = requireString(plugin, "version");

        // --- Optional fields ---
        final String displayName = plugin.has("name") ? plugin.get("name").getAsString() : null;
        final String description = plugin.has("description") ? plugin.get("description").getAsString() : null;

        // --- Contributors ---
        final List<String> contributors = new ArrayList<>();
        if (plugin.has("contributors")) {
            for (final JsonElement entry : plugin.getAsJsonArray("contributors")) {
                final JsonObject contributor = entry.getAsJsonObject();
                if (contributor.has("name")) contributors.add(contributor.get("name").getAsString());
            }
        }

        // --- Links ---
        final List<Link> links = new ArrayList<>();
        if (plugin.has("links")) {
            final JsonObject linksObj = plugin.getAsJsonObject("links");
            if (linksObj.has("homepage")) links.add(new Link("homepage", linksObj.get("homepage").getAsString()));
            if (linksObj.has("source"))   links.add(new Link("sources",  linksObj.get("source").getAsString()));
            if (linksObj.has("issues"))   links.add(new Link("issues",   linksObj.get("issues").getAsString()));
        }

        // --- Dependencies ---
        String mcVersion  = null;
        String apiVersion = null;
        final List<Dependency> dependencies = new ArrayList<>();

        if (plugin.has("dependencies")) {
            for (final JsonElement entry : plugin.getAsJsonArray("dependencies")) {
                final JsonObject dep  = entry.getAsJsonObject();
                final String depId   = dep.has("id")      ? dep.get("id").getAsString()      : null;
                final String depVer  = dep.has("version") ? dep.get("version").getAsString() : null;
                final boolean optional = dep.has("optional") && dep.get("optional").getAsBoolean();

                if (depId == null) continue;

                switch (depId) {
                    case "minecraft" -> mcVersion  = depVer;
                    case "spongeapi" -> apiVersion = depVer;
                    default          -> dependencies.add(new Dependency(depId, depVer, !optional));
                }
            }
        }

        final ModLoaderMeta loaderMeta = new ModLoaderMeta(
                ModLoader.SPONGE,
                apiVersion,
                null,       // loaderVersion — not declared in sponge_plugins.json
                mcVersion != null ? mcVersion : "unknown",
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
                        null,           // license — not present in sponge_plugins.json
                        List.of(),      // authors — not present in sponge_plugins.json
                        contributors,
                        List.of(),      // credits — not present in sponge_plugins.json
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
}
