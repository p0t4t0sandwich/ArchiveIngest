package dev.neuralnexus.archiveingest.data.mca;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
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

    Mod withLoaderSupport(List<LoaderSupport> loaderSupport);

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

            // --- Rebuild primary with merged LoaderSupport if needed ---
            Mod result = mergedLoaderSupport.equals(primary.loaderSupport())
                    ? primary
                    : primary.withLoaderSupport(mergedLoaderSupport);

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
//            case "fabric.mod.json"              -> FabricMod.ingest(jarPath);
//            case "META-INF/neoforge.mods.toml"  -> NeoForgeMod.ingest(jarPath);
            case "META-INF/mods.toml"           -> ForgeMod.ingest(jarPath);
//            case "mcmod.info"                   -> LegacyForgeMod.ingest(jarPath);
//            case "META-INF/sponge_plugins.json" -> SpongeMod.ingest(jarPath);
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
}
