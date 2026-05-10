package dev.neuralnexus.archiveingest.data.mca;

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
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public record SpongeMod(
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

        @Nullable String apiVersionRange
) implements Mod {

    public static SpongeMod ingest(Path jarPath) throws IOException {
        // --- Hash jar ---
        HashUtil.FileHashes hashes = HashUtil.hash(jarPath);
        String id = SnowflakeIdGenerator.next();

        // --- Parse META-INF/sponge_plugins.json ---
        JsonObject plugin;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry spongeJson = (JarEntry) jar.getEntry("META-INF/sponge_plugins.json");
            if (spongeJson == null) throw new IOException("No META-INF/sponge_plugins.json found in jar");

            try (InputStream in = jar.getInputStream(spongeJson)) {
                JsonObject root = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                JsonArray plugins = root.getAsJsonArray("plugins");
                if (plugins == null || plugins.isEmpty()) throw new IOException("No plugin descriptors found in sponge_plugins.json");
                plugin = plugins.get(0).getAsJsonObject();
            }
        }

        // --- Hard required fields ---
        String modId = plugin.has("id") ? plugin.get("id").getAsString() : null;
        if (modId == null) throw new IOException("Missing required field: id");

        String version = plugin.has("version") ? plugin.get("version").getAsString() : null;
        if (version == null) throw new IOException("Missing required field: version");

        // --- Optional fields ---
        String name        = plugin.has("name") ? plugin.get("name").getAsString() : modId;
        String description = plugin.has("description") ? plugin.get("description").getAsString() : null;

        // --- Contributors (objects with "name") ---
        List<String> contributors = new ArrayList<>();
        if (plugin.has("contributors")) {
            for (JsonElement entry : plugin.getAsJsonArray("contributors")) {
                JsonObject contributor = entry.getAsJsonObject();
                if (contributor.has("name")) contributors.add(contributor.get("name").getAsString());
            }
        }

        // --- Links ---
        List<Link> links = new ArrayList<>();
        if (plugin.has("links")) {
            JsonObject linksObj = plugin.getAsJsonObject("links");
            if (linksObj.has("homepage")) links.add(new Link("homepage", linksObj.get("homepage").getAsString()));
            if (linksObj.has("source"))   links.add(new Link("sources",  linksObj.get("source").getAsString()));
            if (linksObj.has("issues"))   links.add(new Link("issues",   linksObj.get("issues").getAsString()));
        }

        // --- Dependencies ---
        String apiVersionRange = null;
        List<String> mcVersions = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();

        if (plugin.has("dependencies")) {
            for (JsonElement entry : plugin.getAsJsonArray("dependencies")) {
                JsonObject dep = entry.getAsJsonObject();
                String depId      = dep.has("id")       ? dep.get("id").getAsString()                  : null;
                String depVersion = dep.has("version")  ? dep.get("version").getAsString()              : null;
                boolean optional  = dep.has("optional") && dep.get("optional").getAsBoolean();

                if (depId == null) continue;

                switch (depId) {
                    case "minecraft"  -> mcVersions.add(depVersion);
                    case "spongeapi"  -> apiVersionRange = depVersion;
                    default           -> dependencies.add(new Dependency(depId, depVersion, !optional));
                }
            }
        }

        // --- LoaderSupport ---
        List<LoaderSupport> loaderSupport = List.of(
                new LoaderSupport(ModLoader.SPONGE, mcVersions, "META-INF/sponge_plugins.json")
        );

        // --- Assemble record ---
        return new SpongeMod(
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
                name,
                version,
                description,
                null,
                List.of(),
                contributors,
                loaderSupport,
                dependencies,
                List.of(),
                Side.BOTH,
                apiVersionRange
        );
    }
}
