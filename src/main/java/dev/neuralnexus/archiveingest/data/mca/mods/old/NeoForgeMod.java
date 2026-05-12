package dev.neuralnexus.archiveingest.data.mca.mods.old;

import com.moandjiezana.toml.Toml;
import dev.neuralnexus.archiveingest.data.HashUtil;
import dev.neuralnexus.archiveingest.data.SnowflakeIdGenerator;
import dev.neuralnexus.archiveingest.data.mca.ArchiveInfo;
import dev.neuralnexus.archiveingest.data.mca.Link;
import dev.neuralnexus.archiveingest.data.mca.mods.Dependency;
import dev.neuralnexus.archiveingest.data.mca.mods.ModLoader;
import dev.neuralnexus.archiveingest.data.mca.mods.ModLoaderMeta;
import dev.neuralnexus.archiveingest.data.mca.mods.Side;
import dev.neuralnexus.archiveingest.data.mca.Source;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public record NeoForgeMod(
        @NonNull String id,
        @NonNull String fileName,
        long size,
        @NonNull HashUtil.Hashes hashes,
        @NonNull List<String> related,
        @NonNull List<Link> links,
        @NonNull ArchiveInfo info,

        @NonNull String modId,
        List<String> names,
        @NonNull String version,
        @Nullable String description,
        @Nullable String license,
        @NonNull List<String> authors,
        @NonNull List<String> contributors,
        @NonNull List<ModLoaderMeta> loaderSupport,
        @NonNull List<Dependency> dependencies,
        @NonNull List<Source> platformRefs,
        @NonNull Side side,

        @Nullable String forgeVersionRange
) implements Mod {
    public static @NonNull NeoForgeMod ingest(Path jarPath) throws IOException {
        // --- Hash jar ---
        HashUtil.Hashes hashes = HashUtil.hash(jarPath);
        String id = SnowflakeIdGenerator.next();

        // --- Parse neoforge.mods.toml ---
        final Toml toml;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry tomlEntry = (JarEntry) jar.getEntry("META-INF/neoforge.mods.toml");
            if (tomlEntry == null) throw new IOException("No META-INF/neoforge.mods.toml found in jar");

            try (InputStream in = jar.getInputStream(tomlEntry)) {
                toml = new Toml().read(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }

        // --- [[mods]] block ---
        List<Map<String, Object>> mods = toml.getList("mods");
        if (mods == null || mods.isEmpty()) throw new IOException("No [[mods]] entries found in neoforge.mods.toml");
        Map<String, Object> mod = mods.getFirst();

        // --- Hard required fields ---
        String modId = (String) mod.get("modId");
        if (modId == null) throw new IOException("Missing required field: modId");

        String version = (String) mod.get("version");
        if (version == null) throw new IOException("Missing required field: version");

        if (version.equals("${file.jarVersion}")) {
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                Manifest manifest = jar.getManifest();
                version = manifest != null
                        ? manifest.getMainAttributes().getValue("Implementation-Version")
                        : null;
            }
            if (version == null) throw new IOException("Could not resolve ${file.jarVersion} from MANIFEST.MF");
        }

        String neoForgeVersionRange = toml.getString("loaderVersion");

        // --- Optional fields ---
        String name        = (String) mod.getOrDefault("displayName", null);
        String description = (String) mod.getOrDefault("description", null);
        String license     = toml.getString("license");

        // --- Authors ---
        List<String> authors = new ArrayList<>();
        Object rawAuthors = mod.get("authors");
        if (rawAuthors instanceof String str) {
            authors = parseAuthors(str);
        } else if (rawAuthors instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof String s) authors.add(s.trim());
            }
        }

        // --- Links ---
        List<Link> links = new ArrayList<>();
        String displayURL = (String) mod.getOrDefault("displayURL", null);
        if (displayURL != null) links.add(new Link("homepage", displayURL));

        // --- Dependencies ---
        String mcVersionRange = null;
        Side side = Side.BOTH;
        List<Dependency> dependencies = new ArrayList<>();

        Object rawDeps = toml.toMap().get("dependencies");
        if (rawDeps instanceof Map<?, ?> depsMap) {
            // [[dependencies.modId]] form
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> modDeps = (List<Map<String, Object>>) depsMap.get(modId);
            if (modDeps != null) {
                for (Map<String, Object> dep : modDeps) {
                    String depId      = (String) dep.get("modId");
                    String depVersion = (String) dep.getOrDefault("versionRange", null);
                    boolean mandatory = (boolean) dep.getOrDefault("mandatory", true);

                    if (depId == null) continue;

                    switch (depId) {
                        case "minecraft" -> {
                            mcVersionRange = depVersion;
                            side = parseSide((String) dep.getOrDefault("side", null));
                        }
                        case "neoforge" -> {
                            if (depVersion != null) neoForgeVersionRange = depVersion;
                        }
                        default -> dependencies.add(new Dependency(depId, depVersion, mandatory));
                    }
                }
            }
        } else if (rawDeps instanceof List<?> depsList) {
            // [[dependencies]] flat form
            for (Object entry : depsList) {
                if (!(entry instanceof Map<?, ?> dep)) continue;
                String depId      = (String) dep.get("modId");
                String depVersion = (String) dep.get("versionRange");
                boolean mandatory = dep.get("mandatory") instanceof Boolean b && b;

                if (depId == null) continue;

                switch (depId) {
                    case "minecraft" -> {
                        mcVersionRange = depVersion;
                        side = parseSide((String) dep.getOrDefault("side", null));
                    }
                    case "neoforge" -> {
                        if (depVersion != null) neoForgeVersionRange = depVersion;
                    }
                    default -> dependencies.add(new Dependency(depId, depVersion, mandatory));
                }
            }
        }

        // --- LoaderSupport ---
        List<String> mcVersions = mcVersionRange != null ? List.of(mcVersionRange) : List.of();
        List<ModLoaderMeta> loaderSupport = List.of(
                new ModLoaderMeta(ModLoader.NEOFORGE, mcVersions, "META-INF/neoforge.mods.toml")
        );

        // --- Assemble record ---
        return new NeoForgeMod(
                id,
                jarPath.getFileName().toString(),
                hashes.size(),
                hashes,
                List.of(),
                links,
                new ArchiveInfo(Instant.now().toEpochMilli(), null, null, List.of()),
                modId,
                name != null ? List.of(name) : List.of(),
                version,
                description,
                license,
                authors,
                List.of(),
                loaderSupport,
                dependencies,
                List.of(),
                side,
                neoForgeVersionRange
        );
    }

// --- Helpers ---

    private static List<String> parseAuthors(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        if (raw.contains(", ")) return Arrays.stream(raw.split(", "))
                .map(String::trim).toList();
        if (raw.contains(" and ")) return Arrays.stream(raw.split(" and "))
                .map(String::trim).toList();
        return List.of(raw.trim());
    }

    private static Side parseSide(String raw) {
        if (raw == null) return Side.BOTH;
        return switch (raw.toUpperCase()) {
            case "CLIENT" -> Side.CLIENT;
            case "SERVER" -> Side.SERVER;
            default       -> Side.BOTH;
        };
    }
}
