package dev.neuralnexus.archiveingest.data.mca;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public interface Mod extends ArchiveItem {
    @NonNull String modId();
    @NonNull String name();
    @NonNull String version();
    @NonNull String description();
    @Nullable String license();
    @NonNull List<String> authors();
    @NonNull List<String> contributors();
    @NonNull List<LoaderSupport> loaderSupport();
    @NonNull List<Dependency> dependencies();
    @NonNull List<PlatformRef> platformRefs();
    @NonNull Side side();

    List<String> KNOWN_META_FILES = List.of(
            "fabric.mod.json",
            "META-INF/neoforge.mods.toml",
            "META-INF/mods.toml",
            "mcmod.info",
            "META-INF/sponge_plugins.json",
            "velocity-plugin.json",
            "plugin.yml",
            "bungee.yml",
            "ignite.mod.json"
    );

    S3Client s3 = S3Client.builder()
            .endpointOverride(URI.create(System.getenv("GARAGE_URL")))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                            System.getenv("GARAGE_ACCESS_KEY"),
                            System.getenv("GARAGE_SECRET_KEY"))))
            .region(Region.of(System.getenv("GARAGE_REGION")))
            .forcePathStyle(true)
            .build();

    String BUCKET = System.getenv("GARAGE_BUCKET");
    Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static Mod ingest(Path jarPath, Path doneDir, Path failedDir) {
        try {
            // --- Detect metadata files ---
            final List<String> detected = new ArrayList<>();
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                for (String metaFile : KNOWN_META_FILES) {
                    if (jar.getEntry(metaFile) != null) detected.add(metaFile);
                }
            }

            if (detected.isEmpty()) {
                throw new IOException("No known metadata files found in jar");
            }

            // --- Primary ingest ---
            final Mod primary = ingestForMeta(jarPath, detected.getFirst());

            // --- Secondary ingests ---
            final List<LoaderSupport> mergedLoaderSupport = new ArrayList<>(primary.loaderSupport());
            for (int i = 1; i < detected.size(); i++) {
                final Mod secondary = ingestForMeta(jarPath, detected.get(i));

                // --- Conflict checks ---
                if (!primary.modId().equals(secondary.modId())) {
                    throw new IOException(String.format(
                            "modId conflict between %s ('%s') and %s ('%s')",
                            detected.getFirst(), primary.modId(),
                            detected.get(i), secondary.modId()
                    ));
                }
                if (!primary.version().equals(secondary.version())) {
                    throw new IOException(String.format(
                            "version conflict between %s ('%s') and %s ('%s')",
                            detected.getFirst(), primary.version(),
                            detected.get(i), secondary.version()
                    ));
                }
                if (!primary.name().equals(secondary.name())) {
                    throw new IOException(String.format(
                            "name conflict between %s ('%s') and %s ('%s')",
                            detected.getFirst(), primary.name(),
                            detected.get(i), secondary.name()
                    ));
                }

                mergedLoaderSupport.addAll(secondary.loaderSupport());
            }

            // --- JiJ processing ---
            List<String> related = new ArrayList<>(primary.related());

            try (JarFile jar = new JarFile(jarPath.toFile())) {
                List<String> jijPaths = new ArrayList<>();

                // Forge/NeoForge
                JarEntry forgeManifest = (JarEntry) jar.getEntry("META-INF/jarjar/manifest.json");
                if (forgeManifest != null) {
                    try (InputStream in = jar.getInputStream(forgeManifest)) {
                        JsonObject manifest = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                        for (JsonElement entry : manifest.getAsJsonArray("jars")) {
                            jijPaths.add(entry.getAsJsonObject().get("path").getAsString());
                        }
                    }
                }

                // Fabric
                JarEntry fabricModJson = (JarEntry) jar.getEntry("fabric.mod.json");
                if (fabricModJson != null) {
                    try (InputStream in = jar.getInputStream(fabricModJson)) {
                        JsonObject fabricMeta = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                        if (fabricMeta.has("jars")) {
                            for (JsonElement entry : fabricMeta.getAsJsonArray("jars")) {
                                String path = entry.getAsJsonObject().get("file").getAsString();
                                if (!jijPaths.contains(path)) jijPaths.add(path);
                            }
                        }
                    }
                }

                // --- Process each nested jar ---
                for (String jijPath : jijPaths) {
                    JarEntry jijEntry = (JarEntry) jar.getEntry(jijPath);
                    if (jijEntry == null) continue;

                    Path tempDir = Files.createTempDirectory("mca-jij-");
                    Path tempFile = tempDir.resolve(Path.of(jijPath).getFileName().toString());
                    try {
                        // Write nested jar to temp file with correct filename
                        try (InputStream in = jar.getInputStream(jijEntry)) {
                            Files.write(tempFile, in.readAllBytes());
                        }

                        // Attempt ingest — soft fail if no recognised metadata
                        final Mod jijMod = ingest(tempFile, doneDir, failedDir);
                        if (jijMod == null) continue;

                        // Check for existing by sha256
                        Optional<ArchivedMod> existing = findExisting(jijMod.modId(), jijMod.version(), jijMod.sha256());
                        related.add(existing.map(ArchivedMod::modId).orElseGet(jijMod::modId));

                    } catch (Exception e) {
                        System.err.println("Skipping JiJ entry " + jijPath + ": " + e.getMessage());
                    } finally {
                        Files.deleteIfExists(tempFile);
                        Files.deleteIfExists(tempDir);
                    }
                }
            }

            final ArchivedMod result = new ArchivedMod(
                    primary.id(),
                    primary.fileName(),
                    primary.size(),
                    primary.md5(),
                    primary.sha1(),
                    primary.sha256(),
                    primary.sha512(),
                    related,
                    primary.links(),
                    primary.info(),
                    primary.modId(),
                    primary.name(),
                    primary.version(),
                    primary.description(),
                    primary.license(),
                    primary.authors(),
                    primary.contributors(),
                    mergedLoaderSupport,
                    primary.dependencies(),
                    primary.platformRefs(),
                    primary.side()
            );

            // --- S3 uploads ---
            final String keyPrefix = "mods/" + result.modId() + "/" + result.version() + "/" + result.id() + "/";

            final String metaJson = GSON.toJson(result);
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(BUCKET)
                            .key(keyPrefix + "meta.json")
                            .contentType("application/json")
                            .build(),
                    RequestBody.fromString(metaJson, StandardCharsets.UTF_8)
            );

            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(BUCKET)
                            .key(keyPrefix + jarPath.getFileName().toString())
                            .contentType("application/java-archive")
                            .build(),
                    RequestBody.fromFile(jarPath)
            );

            // --- Move to doneDir ---
            Files.move(jarPath, doneDir.resolve(jarPath.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);

            return result;

        } catch (Exception e) {
            try {
                Files.createDirectories(failedDir);
                Files.move(jarPath, failedDir.resolve(jarPath.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);

                Map<String, String> error = new LinkedHashMap<>();
                error.put("file", jarPath.getFileName().toString());
                error.put("reason", e.getMessage());
                error.put("timestamp", Instant.now().toString());

                String errorFileName = jarPath.getFileName().toString() + ".error.json";
                Files.writeString(
                        failedDir.resolve(errorFileName),
                        GSON.toJson(error)
                );
            } catch (IOException ex) {
                System.err.println("Failed to move " + jarPath + " to failedDir: " + ex.getMessage());
            }
            return null;
        }
    }

    private static Mod ingestForMeta(Path jarPath, String metaFile) throws IOException {
        return switch (metaFile) {
            case "fabric.mod.json"              -> FabricMod.ingest(jarPath);
            case "META-INF/neoforge.mods.toml"  -> NeoForgeMod.ingest(jarPath);
            case "META-INF/mods.toml"           -> ForgeMod.ingest(jarPath);
            case "mcmod.info"                   -> LegacyForgeMod.ingest(jarPath);
            case "META-INF/sponge_plugins.json" -> SpongeMod.ingest(jarPath);
//            case "velocity-plugin.json"         -> VelocityPlugin.ingest(jarPath);
//            case "plugin.yml"                   -> BukkitPlugin.ingest(jarPath);
//            case "bungee.yml"                   -> BungeeCordPlugin.ingest(jarPath);
//            case "ignite.mod.json"              -> IgniteMod.ingest(jarPath);
            default -> throw new IOException("Unknown metadata file: " + metaFile);
        };
    }

    static void ingestMods(Path inputDir, Path doneDir, Path failedDir) {
        try {
            Files.createDirectories(doneDir);
            Files.createDirectories(failedDir);

            try (var stream = Files.walk(inputDir)) {
                stream.filter(path -> path.toString().endsWith(".jar"))
                        .forEach(jarPath -> {
                            System.out.println("Processing " + jarPath);
                            final Mod mod = ingest(jarPath, doneDir, failedDir);
                            if (mod != null) {
                                System.out.println("Successfully ingested mod: " + mod.modId() + " " + mod.version());
                            } else {
                                System.out.println("Failed to ingest mod: " + jarPath.getFileName());
                            }
                        });
            }
        } catch (IOException e) {
            System.err.println("Failed to process mods in directory: " + e.getMessage());
        }
    }

    private static Optional<ArchivedMod> findExisting(String modId, String version, String sha256) {
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(BUCKET)
                .prefix("mods/" + modId + "/" + version + "/")
                .delimiter("/")
                .build();

        ListObjectsV2Response listing = s3.listObjectsV2(listRequest);

        for (CommonPrefix prefix : listing.commonPrefixes()) {
            final String metaKey = prefix.prefix() + "meta.json";
            try {
                final ResponseBytes<GetObjectResponse> response = s3.getObjectAsBytes(
                        GetObjectRequest.builder()
                                .bucket(BUCKET)
                                .key(metaKey)
                                .build()
                );
                final ArchivedMod existing = GSON.fromJson(response.asUtf8String(), ArchivedMod.class);
                if (sha256.equals(existing.sha256())) {
                    return Optional.of(existing);
                }
            } catch (final Exception e) {
                System.err.println("Skipping " + metaKey + ": " + e.getMessage());
            }
        }

        return Optional.empty();
    }

    record ArchivedMod(
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
            @NonNull Side side
    ) implements Mod {}
}
