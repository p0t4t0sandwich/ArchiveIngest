package dev.neuralnexus.archiveingest.data.players.textures;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.neuralnexus.archiveingest.compression.SevenZip;
import org.jspecify.annotations.NonNull;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Primarily for ingesting PNGs from: <br>
 * 20000000-minecraft-skins <br>
 * 31000000-minecraft-skins
 */
public final class IngestPNG7z {
    public static final DataSource ds;

    static {
        final String username = System.getenv("POSTGRES_USERNAME");
        final String password = System.getenv("POSTGRES_PASSWORD");
        final String database = System.getenv("POSTGRES_DATABASE");
        final String host = System.getenv("POSTGRES_HOST");
        final String port = System.getenv("POSTGRES_PORT");

        final HikariConfig config = new HikariConfig();
        config.setUsername(username);
        config.setPassword(password);
        config.addDataSourceProperty("databaseName", database);
        config.addDataSourceProperty("serverName", host);
        config.addDataSourceProperty("portNumber", port);
        config.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        config.setPoolName("ArchiveIngestPostgreSQLPool");
        config.setConnectionInitSql("SET synchronous_commit = off; SET work_mem = '64MB';");
        ds = new HikariDataSource(config);

        startup();
    }

    private static void startup() {
        final String createTexturesSQL = """
        CREATE TABLE IF NOT EXISTS textures (
            hash TEXT NOT NULL PRIMARY KEY
        );
    """;

        try (final var conn = ds.getConnection();
             final var stmt = conn.createStatement()) {
            stmt.execute(createTexturesSQL);
            System.out.println("Tables are ready.");
        } catch (final Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred while creating the tables.");
        }
    }

    private static final String INSERT_TEXTURE_SQL = """
        INSERT INTO textures (hash) VALUES (?)
        ON CONFLICT (hash) DO NOTHING
        """;

    private static final S3Client s3 = S3Client.builder()
            .endpointOverride(URI.create(System.getenv("GARAGE_URL")))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                            System.getenv("GARAGE_ACCESS_KEY"),
                            System.getenv("GARAGE_SECRET_KEY"))))
            .region(Region.of(System.getenv("GARAGE_REGION")))
            .forcePathStyle(true)
            .build();

    private static final String BUCKET = System.getenv("GARAGE_BUCKET");

    public static void ingestDirOf7z(final @NonNull String dirPath, final @NonNull String outputBaseDir) {
        final Path dir = Paths.get(dirPath);
        final Path outputBasePath = Paths.get(outputBaseDir);
        if (!Files.exists(outputBasePath)) {
            try {
                Files.createDirectories(outputBasePath);
            } catch (final IOException e) {
                //noinspection CallToPrintStackTrace
                e.printStackTrace();
                System.out.println("An error occurred while creating the output base directory.");
                return;
            }
        }
        try (final Stream<Path> paths = Files.walk(dir)) {
            paths.filter(p -> p.toString().endsWith(".7z"))
                    .forEach(p -> {
                        final Path outputDir = outputBasePath.resolve(p.getFileName().toString().replace(".7z", ""));
                        if (Files.exists(outputDir)) {
                            System.out.println("Output directory already exists, skipping decompression for: " + p);
                            ingest(outputDir);
                            return;
                        }
                        SevenZip.decompress(p.toString(), outputDir.toString());
                        ingest(outputDir);
                    });
        } catch (final IOException e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred while walking the directory of 7z files.");
        }
    }

    public static void ingest(final @NonNull Path textureDir) {
        final int UPLOAD_THREADS = 8;
        final long startTime = System.currentTimeMillis();
        final AtomicInteger count = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);

        @SuppressWarnings("resource")
        final ExecutorService executor = Executors.newFixedThreadPool(UPLOAD_THREADS);

        try (final Stream<Path> paths = Files.walk(textureDir)) {
            paths.filter(p -> p.toString().endsWith(".png"))
                    .forEach(p -> executor.submit(() -> {
                        // Filename is in the format of {hash}_{playerName}.png
                        final String hash = p.getFileName().toString()
                                .replace(".png", "")
                                .split("_")[0];
                        final String key = "texture/" + hash;
                        try {
                            if (s3.headObject(b -> b.bucket(BUCKET).key(key)).sdkHttpResponse().isSuccessful()) {
                                final int total = count.incrementAndGet();
                                if (total % 10000 == 0) {
                                    final long elapsed = System.currentTimeMillis() - startTime;
                                    System.out.printf("Uploaded: %d | Elapsed: %ds | Rate: %d files/s%n",
                                            total, elapsed / 1000, total / Math.max(1, elapsed / 1000));
                                }
                                return;
                            }
                        } catch (final NoSuchKeyException ignored) {
                        } catch (final Exception e) {
                            //noinspection CallToPrintStackTrace
                            e.printStackTrace();
                            System.out.println("An error occurred while checking for existing object in S3 for key: " + key);
                        }
                        try {
                            s3.putObject(PutObjectRequest.builder()
                                            .bucket(BUCKET)
                                            .key(key)
                                            .contentType("image/png")
                                            .build(),
                                    RequestBody.fromFile(p));
                            final int total = count.incrementAndGet();
                            if (total % 10000 == 0) {
                                final long elapsed = System.currentTimeMillis() - startTime;
                                System.out.printf("Uploaded: %d | Elapsed: %ds | Rate: %d files/s%n",
                                        total, elapsed / 1000, total / Math.max(1, elapsed / 1000));
                            }
                        } catch (final Exception e) {
                            //noinspection CallToPrintStackTrace
                            e.printStackTrace();
                            failed.incrementAndGet();
                        }
                    }));
        } catch (final IOException e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred while walking the texture directory.");
            return;
        }

        try {
            executor.shutdown();
            //noinspection ResultOfMethodCallIgnored
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
        }

        final long elapsed = System.currentTimeMillis() - startTime;
        System.out.printf("Texture ingestion completed. Uploaded: %d | Failed: %d | Elapsed: %ds | Rate: %d files/s%n",
                count.get(), failed.get(), elapsed / 1000, count.get() / Math.max(1, elapsed / 1000));
        syncTexturesToDB(textureDir);
    }

    public static void syncTexturesToDB(final @NonNull Path textureDir) {
        final int BATCH_SIZE = 50000;
        try (final var conn = ds.getConnection();
             final var stmt = conn.prepareStatement(INSERT_TEXTURE_SQL)) {
            conn.setAutoCommit(false);
            int count = 0;
            try (final Stream<Path> paths = Files.walk(textureDir)) {
                for (final Path p : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".png"))::iterator) {
                    final String hash = p.getFileName().toString()
                            .replace(".png", "")
                            .split("_")[0];
                    stmt.setString(1, hash);
                    stmt.addBatch();
                    if (++count % BATCH_SIZE == 0) {
                        stmt.executeBatch();
                        conn.commit();
                        System.out.printf("Synced: %d%n", count);
                    }
                }
            }
            stmt.executeBatch();
            conn.commit();
            System.out.printf("Sync completed. Total: %d%n", count);
        } catch (final Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred during texture sync.");
        }
    }
}
