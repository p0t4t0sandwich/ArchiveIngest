package dev.neuralnexus.archiveingest.data.mca;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public record FabricMod(
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

        @Nullable String loaderVersionRange
) implements Mod {
    public static @NonNull FabricMod ingest(Path jarPath) throws IOException {
        // --- Hash jar ---
        HashUtil.FileHashes hashes = HashUtil.hash(jarPath);
        String id = SnowflakeIdGenerator.next();

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
        String modId = root.has("id") ? root.get("id").getAsString() : null;
        if (modId == null) throw new IOException("Missing required field: id");

        String version = root.has("version") ? root.get("version").getAsString() : null;
        if (version == null) throw new IOException("Missing required field: version");

        // --- Optional fields ---
        String name        = root.has("name") ? root.get("name").getAsString() : modId;
        String description = root.has("description") ? root.get("description").getAsString() : null;
        String license     = root.has("license") ? root.get("license").getAsString() : null;

        // --- Authors (string or object with "name") ---
        List<String> authors = new ArrayList<>();
        if (root.has("authors")) {
            for (JsonElement entry : root.getAsJsonArray("authors")) {
                if (entry.isJsonPrimitive()) {
                    authors.add(entry.getAsString());
                } else if (entry.isJsonObject()) {
                    JsonObject authorObj = entry.getAsJsonObject();
                    if (authorObj.has("name")) authors.add(authorObj.get("name").getAsString());
                }
            }
        }

        // --- Contributors (same shape as authors) ---
        List<String> contributors = new ArrayList<>();
        if (root.has("contributors")) {
            for (JsonElement entry : root.getAsJsonArray("contributors")) {
                if (entry.isJsonPrimitive()) {
                    contributors.add(entry.getAsString());
                } else if (entry.isJsonObject()) {
                    JsonObject contributorObj = entry.getAsJsonObject();
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
        List<Link> links = new ArrayList<>();
        if (root.has("contact")) {
            JsonObject contact = root.getAsJsonObject("contact");
            if (contact.has("homepage")) links.add(new Link("home_page", contact.get("homepage").getAsString()));
            if (contact.has("sources"))  links.add(new Link("sources",   contact.get("sources").getAsString()));
            if (contact.has("issues"))   links.add(new Link("issues",    contact.get("issues").getAsString()));
        }

        // --- Depends block ---
        String loaderVersionRange = null;
        String mcVersionRange     = null;
        List<Dependency> dependencies = new ArrayList<>();

        if (root.has("depends")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("depends").entrySet()) {
                String depId      = entry.getKey();
                String depVersion = entry.getValue().isJsonPrimitive()
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
        List<String> mcVersions = mcVersionRange != null ? List.of(mcVersionRange) : List.of();
        List<LoaderSupport> loaderSupport = List.of(
                new LoaderSupport(ModLoader.FABRIC, mcVersions, "fabric.mod.json")
        );

        // --- Assemble record ---
        return new FabricMod(
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
                contributors,
                loaderSupport,
                dependencies,
                List.of(),
                side,
                loaderVersionRange
        );
    }
}
