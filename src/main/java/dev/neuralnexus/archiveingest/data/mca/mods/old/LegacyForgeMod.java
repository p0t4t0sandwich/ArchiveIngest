package dev.neuralnexus.archiveingest.data.mca.mods.old;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public record LegacyForgeMod(
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
        @NonNull Side side
) implements Mod {
    public static LegacyForgeMod ingest(Path jarPath) throws IOException {
        // --- Hash jar ---
        HashUtil.Hashes hashes = HashUtil.hash(jarPath);
        String id = SnowflakeIdGenerator.next();

        // --- Parse mcmod.info ---
        JsonArray root;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry mcmodInfo = (JarEntry) jar.getEntry("mcmod.info");
            if (mcmodInfo == null) throw new IOException("No mcmod.info found in jar");

            try (InputStream in = jar.getInputStream(mcmodInfo)) {
                root = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonArray.class);
            }
        }

        if (root == null || root.isEmpty()) throw new IOException("No mod descriptors found in mcmod.info");
        JsonObject mod = root.get(0).getAsJsonObject();

        // --- Hard required fields ---
        String modId = mod.has("modid") ? mod.get("modid").getAsString() : null;
        if (modId == null) throw new IOException("Missing required field: modid");

        String version = mod.has("version") ? mod.get("version").getAsString() : null;
        if (version == null) throw new IOException("Missing required field: version");

        if (version.equals("${version}")) {
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                Manifest manifest = jar.getManifest();
                if (manifest != null) {
                    version = manifest.getMainAttributes().getValue("Implementation-Version");
                } else {
                    version = null;
                }
            }
            if (version == null) throw new IOException("Could not resolve ${version} from MANIFEST.MF");
        }

        // --- Optional fields ---
        String name        = mod.has("name") ? mod.get("name").getAsString() : modId;
        String description = mod.has("description") ? mod.get("description").getAsString() : null;

        // --- Authors ---
        List<String> authors = new ArrayList<>();
        if (mod.has("authorList")) {
            for (JsonElement entry : mod.getAsJsonArray("authorList")) {
                authors.add(entry.getAsString());
            }
        }

        // --- Links ---
        List<Link> links = new ArrayList<>();
        if (mod.has("url")) {
            String url = mod.get("url").getAsString();
            if (!url.isBlank()) links.add(new Link("homepage", url));
        }

        // --- MC version ---
        List<String> mcVersions = new ArrayList<>();
        if (mod.has("mcversion")) {
            String mcVersion = mod.get("mcversion").getAsString();
            if (!mcVersion.isBlank()) mcVersions.add(mcVersion);
        }

        // --- Dependencies ---
        List<Dependency> dependencies = new ArrayList<>();
        if (mod.has("dependencies")) {
            for (JsonElement entry : mod.getAsJsonArray("dependencies")) {
                String depId = entry.getAsString();
                if (!depId.isBlank()) dependencies.add(new Dependency(depId, "[,]", true));
            }
        }

        // --- LoaderSupport ---
        List<ModLoaderMeta> loaderSupport = List.of(
                new ModLoaderMeta(ModLoader.FORGE, mcVersions, "mcmod.info")
        );

        // --- Assemble record ---
        return new LegacyForgeMod(
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
                null,
                authors,
                List.of(),
                loaderSupport,
                dependencies,
                List.of(),
                Side.BOTH
        );
    }
}
