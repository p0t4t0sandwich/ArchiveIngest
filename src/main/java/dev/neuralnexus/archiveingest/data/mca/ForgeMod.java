package dev.neuralnexus.archiveingest.data.mca;

import com.moandjiezana.toml.Toml;

import dev.neuralnexus.archiveingest.data.HashUtil;
import dev.neuralnexus.archiveingest.data.SnowflakeIdGenerator;

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

public record ForgeMod(
        @NonNull String id,
        @NonNull String fileName,
        long size,
        @NonNull String md5,
        @NonNull String sha1,
        @NonNull String sha256,
        @NonNull String sha512,
        @NonNull List<String> related,
        @NonNull List<Link> links,
        @NonNull ArchiveInfo info,

        @NonNull String modId,
        @NonNull String name,
        @NonNull String version,
        @Nullable String description,
        @Nullable String license,
        @NonNull List<String> authors,
        @NonNull List<String> contributors,
        @NonNull List<LoaderSupport> loaderSupport,
        @NonNull List<Dependency> dependencies,
        @NonNull List<PlatformRef> platformRefs,
        @NonNull Side side,

        @Nullable String forgeVersionRange
) implements Mod {
    public static @NonNull ForgeMod ingest(Path jarPath) throws IOException {
        // --- Hash jar ---
        HashUtil.FileHashes hashes = HashUtil.hash(jarPath);
        String id = SnowflakeIdGenerator.next();

        // --- Parse mods.toml ---
        final Toml toml;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry tomlEntry = (JarEntry) jar.getEntry("META-INF/mods.toml");
            if (tomlEntry == null) throw new IOException("No META-INF/mods.toml found in jar");

            try (InputStream in = jar.getInputStream(tomlEntry)) {
                toml = new Toml().read(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }

        // --- [[mods]] block ---
        List<Map<String, Object>> mods = toml.getList("mods");
        if (mods == null || mods.isEmpty()) throw new IOException("No [[mods]] entries found in mods.toml");
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

        String forgeVersionRange = toml.getString("loaderVersion");

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
                        case "forge" -> {
                            if (depVersion != null) forgeVersionRange = depVersion;
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
                    case "forge" -> {
                        if (depVersion != null) forgeVersionRange = depVersion;
                    }
                    default -> dependencies.add(new Dependency(depId, depVersion, mandatory));
                }
            }
        }

        // --- LoaderSupport ---
        List<String> mcVersions = mcVersionRange != null ? List.of(mcVersionRange) : List.of();
        List<LoaderSupport> loaderSupport = List.of(
                new LoaderSupport(ModLoader.FORGE, mcVersions, "META-INF/mods.toml")
        );

        // --- Assemble record ---
        return new ForgeMod(
                id,
                jarPath.getFileName().toString(),
                hashes.size(),
                hashes.md5(),
                hashes.sha1(),
                hashes.sha256(),
                hashes.sha512(),
                List.of(),
                links,
                new ArchiveInfo(Instant.now().toEpochMilli(), null, null, List.of()),
                modId,
                Mod.normalizeName(name != null ? name : modId),
                version,
                description,
                license,
                authors,
                List.of(),
                loaderSupport,
                dependencies,
                List.of(),
                side,
                forgeVersionRange
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
