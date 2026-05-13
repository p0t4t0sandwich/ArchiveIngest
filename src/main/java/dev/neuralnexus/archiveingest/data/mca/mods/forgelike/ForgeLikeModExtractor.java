package dev.neuralnexus.archiveingest.data.mca.mods.forgelike;

import com.moandjiezana.toml.Toml;
import dev.neuralnexus.archiveingest.data.mca.Link;
import dev.neuralnexus.archiveingest.data.mca.mods.Dependency;
import dev.neuralnexus.archiveingest.data.mca.mods.ExtractResult;
import dev.neuralnexus.archiveingest.data.mca.mods.ModLoader;
import dev.neuralnexus.archiveingest.data.mca.mods.ModLoaderMeta;
import dev.neuralnexus.archiveingest.data.mca.mods.ModVersion;
import dev.neuralnexus.archiveingest.data.mca.mods.Side;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

final class ForgeLikeModExtractor {

    private ForgeLikeModExtractor() {}

    static @NonNull ExtractResult extract(
            final long archiveItemId,
            final @NonNull Path jarPath,
            final @NonNull String metaFile
    ) throws IOException {
        final Toml toml;
        try (final JarFile jar = new JarFile(jarPath.toFile())) {
            final JarEntry entry = (JarEntry) jar.getEntry(metaFile);
            if (entry == null) throw new IOException("No " + metaFile + " found in jar");
            try (final InputStream in = jar.getInputStream(entry)) {
                toml = new Toml().read(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }

        // --- [[mods]] block ---
        final List<Map<String, Object>> mods = toml.getList("mods");
        if (mods == null || mods.isEmpty()) throw new IOException("No [[mods]] entries in " + metaFile);
        final Map<String, Object> mod = mods.getFirst();

        // --- Required fields ---
        final String modId   = requireString(mod, "modId");
        final String version = resolveVersion(mod, jarPath);

        // --- Optional fields ---
        final String displayName = (String) mod.getOrDefault("displayName", null);
        final String description = (String) mod.getOrDefault("description", null);
        final String license     = toml.getString("license");

        // --- Authors / credits ---
        final List<String> authors = parseAuthors(mod.get("authors"));
        final List<String> credits = parseAuthors(mod.get("credits"));

        // --- Links ---
        final List<Link> links = new ArrayList<>();
        final String displayURL    = (String) mod.getOrDefault("displayURL", null);
        final String updateJSONURL = (String) mod.getOrDefault("updateJSONURL", null);
        if (displayURL != null)    links.add(new Link("homepage", displayURL));
        if (updateJSONURL != null) links.add(new Link("update_json", updateJSONURL));

        // --- Side from displayTest ---
        Side side = parseDisplayTest((String) mod.getOrDefault("displayTest", null));

        // --- Dependencies + loader meta ---
        String mcVersion     = null;
        String forgeVersion  = null;
        String neoVersion    = null;
        final String loaderVersion = toml.getString("loaderVersion");
        final List<Dependency> dependencies = new ArrayList<>();

        final Object rawDeps = toml.toMap().get("dependencies");
        final List<Map<String, Object>> depsList = resolveDeps(rawDeps, modId);
        if (depsList != null) {
            for (final Map<String, Object> dep : depsList) {
                final String depId      = (String) dep.get("modId");
                final String depVersion = (String) dep.getOrDefault("versionRange", null);
                final boolean mandatory = dep.get("mandatory") instanceof Boolean b && b;
                if (depId == null) continue;
                switch (depId) {
                    case "minecraft" -> {
                        mcVersion = depVersion;
                        if (side == null) side = parseSide((String) dep.getOrDefault("side", null));
                    }
                    case "forge"    -> forgeVersion = depVersion;
                    case "neoforge" -> neoVersion   = depVersion;
                }
                dependencies.add(new Dependency(depId, depVersion, mandatory));
            }
        }
        if (side == null) side = Side.BOTH;

        // --- Loader meta ---
        final String mc = mcVersion != null ? mcVersion : "unknown";
        final List<ModLoaderMeta> loaderMetas = new ArrayList<>();
        if (forgeVersion != null) {
            loaderMetas.add(new ModLoaderMeta(ModLoader.FORGE, forgeVersion, loaderVersion, mc, metaFile));
        }
        if (neoVersion != null) {
            loaderMetas.add(new ModLoaderMeta(ModLoader.NEOFORGE, neoVersion, loaderVersion, mc, metaFile));
        }
        if (loaderMetas.isEmpty()) {
            // loader-agnostic — add both
            loaderMetas.add(new ModLoaderMeta(ModLoader.FORGE,    null, loaderVersion, mc, metaFile));
            loaderMetas.add(new ModLoaderMeta(ModLoader.NEOFORGE, null, loaderVersion, mc, metaFile));
        }

        return new ExtractResult(
                new ModVersion(
                        archiveItemId,
                        modId,
                        displayName != null ? List.of(displayName) : List.of(),
                        version,
                        description,
                        license,
                        authors,
                        List.of(),
                        credits,
                        loaderMetas,
                        dependencies,
                        side
                ),
                links
        );
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static @Nullable List<Map<String, Object>> resolveDeps(final Object rawDeps, final String modId) {
        if (rawDeps instanceof Map<?, ?> depsMap) return (List<Map<String, Object>>) depsMap.get(modId);
        if (rawDeps instanceof List<?> depsList)  return (List<Map<String, Object>>) depsList;
        return null;
    }

    private static String resolveVersion(final Map<String, Object> mod, final Path jarPath) throws IOException {
        String version = (String) mod.get("version");
        if (version == null) throw new IOException("Missing required field: version");
        if (!version.equals("${file.jarVersion}")) return version;
        try (final JarFile jar = new JarFile(jarPath.toFile())) {
            final Manifest manifest = jar.getManifest();
            final String resolved = manifest != null
                    ? manifest.getMainAttributes().getValue("Implementation-Version")
                    : null;
            if (resolved == null) throw new IOException("Could not resolve ${file.jarVersion} from MANIFEST.MF");
            return resolved;
        }
    }

    private static String requireString(final Map<String, Object> map, final String key) throws IOException {
        final String val = (String) map.get(key);
        if (val == null || val.isBlank()) throw new IOException("Missing required field: " + key);
        return val.trim();
    }

    private static @Nullable Side parseDisplayTest(final @Nullable String raw) {
        if (raw == null) return null;
        return switch (raw.toUpperCase()) {
            case "MATCH_VERSION"          -> Side.BOTH;
            case "IGNORE_SERVER_VERSION"  -> Side.SERVER;
            case "IGNORE_ALL_VERSION", "NONE" -> Side.UNKNOWN;
            default -> null;
        };
    }

    private static Side parseSide(final @Nullable String raw) {
        if (raw == null) return Side.BOTH;
        return switch (raw.toUpperCase()) {
            case "CLIENT" -> Side.CLIENT;
            case "SERVER" -> Side.SERVER;
            default       -> Side.BOTH;
        };
    }

    private static List<String> parseAuthors(final Object raw) {
        switch (raw) {
            case null -> { return List.of(); }
            case List<?> list -> {
                return list.stream()
                        .filter(e -> e instanceof String)
                        .map(e -> ((String) e).trim())
                        .toList();
            }
            case String str -> {
                if (str.isBlank()) return List.of();
                if (str.contains(", "))   return Arrays.stream(str.split(", ")).map(String::trim).toList();
                if (str.contains(" and ")) return Arrays.stream(str.split(" and ")).map(String::trim).toList();
                return List.of(str.trim());
            }
            default -> { return List.of(); }
        }
    }
}
