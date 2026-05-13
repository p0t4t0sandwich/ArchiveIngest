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

public final class ForgeModExtractor {
    private static final String META_FILE = "META-INF/mods.toml";

    private ForgeModExtractor() {}

    public static boolean supports(final @NonNull JarFile jar) {
        return jar.getEntry(META_FILE) != null;
    }

    public static @NonNull ExtractResult extract(final long archiveItemId, final @NonNull Path jarPath) throws IOException {
        final Toml toml;
        try (final JarFile jar = new JarFile(jarPath.toFile())) {
            final JarEntry entry = (JarEntry) jar.getEntry(META_FILE);
            if (entry == null) throw new IOException("No " + META_FILE + " found in jar");
            try (final InputStream in = jar.getInputStream(entry)) {
                toml = new Toml().read(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }

        // --- [[mods]] block ---
        final List<Map<String, Object>> mods = toml.getList("mods");
        if (mods == null || mods.isEmpty()) throw new IOException("No [[mods]] entries in " + META_FILE);
        final Map<String, Object> mod = mods.getFirst();

        // --- Required fields ---
        final String modId   = requireString(mod, "modId");
        final String version = resolveVersion(mod, jarPath);

        // --- Optional fields ---
        final String displayName = (String) mod.getOrDefault("displayName", null);
        final String description = (String) mod.getOrDefault("description", null);
        final String license     = toml.getString("license");

        // --- Authors ---
        final List<String> authors = parseAuthors(mod.get("authors"));

        // --- Credits ---
        final List<String> credits = parseAuthors(mod.get("credits"));

        // --- Links ---
        final List<Link> links = new ArrayList<>();
        final String displayURL = (String) mod.getOrDefault("displayURL", null);
        if (displayURL != null) links.add(new Link("homepage", displayURL));
        final String updateJSONURL = (String) mod.getOrDefault("updateJSONURL", null);
        if (updateJSONURL != null) links.add(new Link("update_json", updateJSONURL));

        // --- Dependencies + loader meta ---
        String mcVersion     = null;
        String apiVersion    = null;
        final String loaderVersion = toml.getString("loaderVersion");
        Side side            = parseDisplayTest((String) mod.getOrDefault("displayTest", null));
        final List<Dependency> dependencies = new ArrayList<>();

        final Object rawDeps = toml.toMap().get("dependencies");
        if (rawDeps instanceof Map<?, ?> depsMap) {
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> modDeps = (List<Map<String, Object>>) depsMap.get(modId);
            if (modDeps != null) {
                for (final Map<String, Object> dep : modDeps) {
                    final String depId      = (String) dep.get("modId");
                    final String depVersion = (String) dep.getOrDefault("versionRange", null);
                    final boolean mandatory = dep.get("mandatory") instanceof Boolean b && b;
                    if (depId == null) continue;
                    if (depId.equals("minecraft")) {
                        mcVersion = depVersion;
                        if (side == null) side = parseSide((String) dep.getOrDefault("side", null));
                    } else if (depId.equals("forge")) {
                        apiVersion = depVersion;
                    }
                    dependencies.add(new Dependency(depId, depVersion, mandatory));
                }
            }
        } else if (rawDeps instanceof List<?> depsList) {
            for (final Object entry : depsList) {
                if (!(entry instanceof Map<?, ?> dep)) continue;
                final String depId      = (String) dep.get("modId");
                final String depVersion = (String) dep.getOrDefault("versionRange", null);
                final boolean mandatory = dep.get("mandatory") instanceof Boolean b && b;
                if (depId == null) continue;
                if (depId.equals("minecraft")) {
                    mcVersion = depVersion;
                    if (side == null) side = parseSide((String) dep.getOrDefault("side", null));
                } else if (depId.equals("forge")) {
                    apiVersion = depVersion;
                }
                dependencies.add(new Dependency(depId, depVersion, mandatory));
            }
        }
        if (side == null) side = Side.BOTH;

        final ModLoaderMeta loaderMeta = new ModLoaderMeta(
                ModLoader.FORGE,
                apiVersion,
                loaderVersion,
                mcVersion != null ? mcVersion : "unknown",
                META_FILE
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
                    List.of(), // contributors
                    credits,
                    List.of(loaderMeta),
                    dependencies,
                    side
                ),
                links
        );
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String resolveVersion(final Map<String, Object> mod, final @NonNull Path jarPath) throws IOException {
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

    private static String requireString(final Map<String, Object> map, final @NonNull String key) throws IOException {
        final String val = (String) map.get(key);
        if (val == null || val.isBlank()) throw new IOException("Missing required field: " + key);
        return val.trim();
    }

    private static @Nullable Side parseDisplayTest(final @Nullable String raw) {
        if (raw == null) return null;
        return switch (raw.toUpperCase()) {
            case "MATCH_VERSION", "IGNORE_ALL_VERSION", "NONE" -> Side.BOTH;
            case "IGNORE_SERVER_VERSION" -> Side.SERVER;
            default -> null;
        };
    }

    private static List<String> parseAuthors(final @Nullable Object raw) {
        switch (raw) {
            case null -> {
                return List.of();
            }
            case List<?> list -> {
                return list.stream()
                        .filter(e -> e instanceof String)
                        .map(e -> ((String) e).trim())
                        .toList();
            }
            case String str -> {
                if (str.isBlank()) return List.of();
                if (str.contains(", ")) return Arrays.stream(str.split(", ")).map(String::trim).toList();
                if (str.contains(" and ")) return Arrays.stream(str.split(" and ")).map(String::trim).toList();
                return List.of(str.trim());
            }
            default -> {}
        }
        return List.of();
    }

    private static Side parseSide(final @Nullable String raw) {
        if (raw == null) return Side.BOTH;
        return switch (raw.toUpperCase()) {
            case "CLIENT" -> Side.CLIENT;
            case "SERVER" -> Side.SERVER;
            default       -> Side.BOTH;
        };
    }
}
