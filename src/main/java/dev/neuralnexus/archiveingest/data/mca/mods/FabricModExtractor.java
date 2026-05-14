package dev.neuralnexus.archiveingest.data.mca.mods;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.neuralnexus.archiveingest.data.mca.Link;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class FabricModExtractor {
    private static final String META_FILE = "fabric.mod.json";

    private FabricModExtractor() {}

    public static boolean supports(final @NonNull JarFile jar) {
        return jar.getEntry(META_FILE) != null;
    }

    public static @NonNull ExtractResult extract(final long archiveItemId, final @NonNull Path jarPath) throws IOException {
        final JsonObject root;
        try (final JarFile jar = new JarFile(jarPath.toFile())) {
            final JarEntry entry = (JarEntry) jar.getEntry(META_FILE);
            if (entry == null) throw new IOException("No " + META_FILE + " found in jar");
            try (final InputStream in = jar.getInputStream(entry)) {
                root = ModArchive.GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            }
        }

        // --- Required fields ---
        final String modId  = requireString(root, "id");
        final String version = requireString(root, "version");

        // --- Optional fields ---
        final String displayName = root.has("name") ? root.get("name").getAsString() : null;
        final String description = root.has("description") ? root.get("description").getAsString() : null;
        final String license     = root.has("license") ? root.get("license").getAsString() : null;

        // --- Authors ---
        final List<String> authors = parsePeople(root, "authors");

        // --- Contributors ---
        final List<String> contributors = parsePeople(root, "contributors");

        // --- Side ---
        final Side side;
        if (root.has("environment")) {
            side = switch (root.get("environment").getAsString()) {
                case "client" -> Side.CLIENT;
                case "server" -> Side.SERVER;
                default       -> Side.BOTH;
            };
        } else {
            side = Side.BOTH;
        }

        // --- Links ---
        final List<Link> links = new ArrayList<>();
        if (root.has("contact")) {
            final JsonObject contact = root.getAsJsonObject("contact");
            if (contact.has("homepage")) links.add(new Link("homepage", contact.get("homepage").getAsString()));
            if (contact.has("sources"))  links.add(new Link("sources",  contact.get("sources").getAsString()));
            if (contact.has("issues"))   links.add(new Link("issues",   contact.get("issues").getAsString()));
        }

        // --- Depends ---
        String mcVersion     = null;
        String loaderVersion = null;
        String apiVersion    = null;
        final List<Dependency> dependencies = new ArrayList<>();

        if (root.has("depends")) {
            for (final Map.Entry<String, JsonElement> entry : root.getAsJsonObject("depends").entrySet()) {
                final String depId      = entry.getKey();
                final String depVersion = entry.getValue().isJsonPrimitive()
                        ? entry.getValue().getAsString()
                        : null;

                switch (depId) {
                    case "minecraft"    -> mcVersion     = depVersion;
                    case "fabricloader" -> loaderVersion = depVersion;
                    case "fabric-api",
                         "fabric"      -> apiVersion    = depVersion;
                    default            -> dependencies.add(new Dependency(depId, depVersion, true));
                }
            }
        }

        final ModLoaderMeta loaderMeta = new ModLoaderMeta(
                ModLoader.FABRIC,
                apiVersion,
                loaderVersion,
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
                        license,
                        authors,
                        contributors,
                        List.of(),      // credits — not present in fabric.mod.json
                        List.of(loaderMeta),
                        side
                ),
                links
        );
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static @NonNull String requireString(final @NonNull JsonObject obj, final @NonNull String key) throws IOException {
        if (!obj.has(key) || obj.get(key).getAsString().isBlank())
            throw new IOException("Missing required field: " + key);
        return obj.get(key).getAsString();
    }

    private static List<String> parsePeople(final @NonNull JsonObject root, final @NonNull String key) {
        final List<String> result = new ArrayList<>();
        if (!root.has(key)) return result;
        for (final JsonElement entry : root.getAsJsonArray(key)) {
            if (entry.isJsonPrimitive()) {
                result.add(entry.getAsString());
            } else if (entry.isJsonObject()) {
                final JsonObject obj = entry.getAsJsonObject();
                if (obj.has("name")) result.add(obj.get("name").getAsString());
            }
        }
        return result;
    }
}
