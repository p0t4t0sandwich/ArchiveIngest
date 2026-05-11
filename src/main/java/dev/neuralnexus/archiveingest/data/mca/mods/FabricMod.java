package dev.neuralnexus.archiveingest.data.mca.mods;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.neuralnexus.archiveingest.data.HashUtil;
import dev.neuralnexus.archiveingest.data.SnowflakeIdGenerator;
import dev.neuralnexus.archiveingest.data.mca.ArchiveInfo;
import dev.neuralnexus.archiveingest.data.mca.Hashes;
import dev.neuralnexus.archiveingest.data.mca.Link;
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
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public record FabricMod(
        @NonNull String id,
        @NonNull String fileName,
        long size,
        @NonNull Hashes hashes,
        List<String> related,
        List<Link> links,
        @NonNull ArchiveInfo info,

        @NonNull String modId,
        List<String> names,
        @NonNull String version,
        @Nullable String description,
        @Nullable String license,
        List<String> authors,
        List<String> contributors,
        List<LoaderSupport> loaderSupport,
        List<Dependency> dependencies,
        List<PlatformRef> platformRefs,
        @NonNull Side side,

        @Nullable String loaderVersionRange
) implements Mod {
    public static @NonNull FabricMod ingest(Path jarPath) throws IOException {
        // --- Hash jar ---
        final Hashes hashes = HashUtil.hash(jarPath);
        final String id = SnowflakeIdGenerator.next();

        // --- Parse fabric.mod.json ---
        JsonObject root;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry fabricModJson = (JarEntry) jar.getEntry("fabric.mod.json");
            if (fabricModJson == null) throw new IOException("No fabric.mod.json found in jar");

            try (InputStream in = jar.getInputStream(fabricModJson)) {
                root = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            }
        }

        // --- Hard required fields ---
        final String modId = root.has("id") ? root.get("id").getAsString() : null;
        if (modId == null) throw new IOException("Missing required field: id");

        final String version = root.has("version") ? root.get("version").getAsString() : null;
        if (version == null) throw new IOException("Missing required field: version");

        // --- Optional fields ---
        final String name        = root.has("name") ? root.get("name").getAsString() : modId;
        final String description = root.has("description") ? root.get("description").getAsString() : null;
        final String license     = root.has("license") ? root.get("license").getAsString() : null;

        // --- Authors (string or object with "name") ---
        final List<String> authors = new ArrayList<>();
        if (root.has("authors")) {
            for (final JsonElement entry : root.getAsJsonArray("authors")) {
                if (entry.isJsonPrimitive()) {
                    authors.add(entry.getAsString());
                } else if (entry.isJsonObject()) {
                    final JsonObject authorObj = entry.getAsJsonObject();
                    if (authorObj.has("name")) authors.add(authorObj.get("name").getAsString());
                }
            }
        }

        // --- Contributors (same shape as authors) ---
        final List<String> contributors = new ArrayList<>();
        if (root.has("contributors")) {
            for (final JsonElement entry : root.getAsJsonArray("contributors")) {
                if (entry.isJsonPrimitive()) {
                    contributors.add(entry.getAsString());
                } else if (entry.isJsonObject()) {
                    final JsonObject contributorObj = entry.getAsJsonObject();
                    if (contributorObj.has("name")) contributors.add(contributorObj.get("name").getAsString());
                }
            }
        }

        // --- Side ---
        Side side = Side.BOTH;
        if (root.has("environment")) {
            side = switch (root.get("environment").getAsString()) {
                case "client" -> Side.CLIENT;
                case "server" -> Side.SERVER;
                default       -> Side.BOTH;
            };
        }

        // --- Links ---
        final List<Link> links = new ArrayList<>();
        if (root.has("contact")) {
            final JsonObject contact = root.getAsJsonObject("contact");
            if (contact.has("homepage")) links.add(new Link("homepage",  contact.get("homepage").getAsString()));
            if (contact.has("sources"))  links.add(new Link("sources",   contact.get("sources").getAsString()));
            if (contact.has("issues"))   links.add(new Link("issues",    contact.get("issues").getAsString()));
        }

        // --- Depends block ---
        String loaderVersionRange = null;
        String mcVersionRange     = null;
        final List<Dependency> dependencies = new ArrayList<>();

        if (root.has("depends")) {
            for (final Map.Entry<String, JsonElement> entry : root.getAsJsonObject("depends").entrySet()) {
                final String depId      = entry.getKey();
                final String depVersion = entry.getValue().isJsonPrimitive()
                        ? entry.getValue().getAsString()
                        : null;

                switch (depId) {
                    case "minecraft"    -> mcVersionRange     = depVersion;
                    case "fabricloader" -> loaderVersionRange = depVersion;
                    default             -> dependencies.add(new Dependency(depId, depVersion, true));
                }
            }
        }

        // --- LoaderSupport ---
        final List<String> mcVersions = mcVersionRange != null ? List.of(mcVersionRange) : List.of();
        final List<LoaderSupport> loaderSupport = List.of(
                new LoaderSupport(ModLoader.FABRIC, mcVersions, "fabric.mod.json")
        );

        // --- Assemble record ---
        return new FabricMod(
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
                contributors,
                loaderSupport,
                dependencies,
                List.of(),
                side,
                loaderVersionRange
        );
    }
}
