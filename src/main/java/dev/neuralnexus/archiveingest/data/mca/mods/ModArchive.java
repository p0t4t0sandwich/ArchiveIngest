package dev.neuralnexus.archiveingest.data.mca.mods;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import dev.neuralnexus.archiveingest.data.mca.Link;
import dev.neuralnexus.archiveingest.data.HashUtil;
import dev.neuralnexus.archiveingest.data.HashUtil.Hashes;
import dev.neuralnexus.archiveingest.data.SnowflakeIdGenerator;

import dev.neuralnexus.archiveingest.data.mca.Source;
import dev.neuralnexus.archiveingest.data.mca.libraries.JavaLibrary;
import dev.neuralnexus.archiveingest.data.mca.mods.forgelike.FMLManifestExtractor;
import dev.neuralnexus.archiveingest.data.mca.mods.forgelike.ForgeModExtractor;
import org.jspecify.annotations.NonNull;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class ModArchive {

    private ModArchive() {}

    public static final String ARCHIVE_INGEST_USER;
    static {
        ARCHIVE_INGEST_USER = System.getenv("ARCHIVE_INGEST_USER");
        if (ARCHIVE_INGEST_USER == null || ARCHIVE_INGEST_USER.isBlank()) {
            throw new IllegalStateException("ARCHIVE_INGEST_USER environment variable must be set and non-blank");
        }
    }

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final S3Client s3 = S3Client.builder()
            .endpointOverride(URI.create(System.getenv("GARAGE_URL")))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                            System.getenv("GARAGE_ACCESS_KEY"),
                            System.getenv("GARAGE_SECRET_KEY"))))
            .region(Region.of(System.getenv("GARAGE_REGION")))
            .forcePathStyle(true)
            .build();

    public static final String S3_BUCKET = System.getenv("GARAGE_BUCKET");

    public static final DataSource ds;

    static {
        final HikariConfig config = new HikariConfig();
        config.setUsername(System.getenv("POSTGRES_USERNAME"));
        config.setPassword(System.getenv("POSTGRES_PASSWORD"));
        config.addDataSourceProperty("databaseName", System.getenv("POSTGRES_DATABASE"));
        config.addDataSourceProperty("serverName", System.getenv("POSTGRES_HOST"));
        config.addDataSourceProperty("portNumber", System.getenv("POSTGRES_PORT"));
        config.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        config.setPoolName("ArchiveIngestMCAPostgreSQLPool");
        ds = new HikariDataSource(config);

        startup();
    }

    private static final String ARRAY_UNION_SQL = """
            CREATE OR REPLACE FUNCTION array_union_text(a text[], b text[])
            RETURNS text[] AS $$
                SELECT ARRAY(SELECT DISTINCT unnest(a || b) ORDER BY 1)
            $$ LANGUAGE sql IMMUTABLE;
            """;

    private static final String CREATE_ARCHIVE_ITEMS_SQL = """
            CREATE TABLE IF NOT EXISTS archive_items (
                id            BIGINT      PRIMARY KEY,
                file_name     TEXT        NOT NULL,
                size          BIGINT      NOT NULL,
                md5           CHAR(32)    NOT NULL,
                sha1          CHAR(40)    NOT NULL,
                sha256        CHAR(64)    NOT NULL,
                sha512        CHAR(128)   NOT NULL,
                related       TEXT[]      NOT NULL DEFAULT '{}',
                archived_at   BIGINT      NOT NULL,
                archived_by   TEXT
            );
            """;

    private static final String CREATE_ARCHIVE_ITEM_LINKS_SQL = """
            CREATE TABLE IF NOT EXISTS archive_item_links (
                id                BIGINT  PRIMARY KEY,
                archive_item_id   BIGINT  NOT NULL REFERENCES archive_items(id) ON DELETE CASCADE,
                rel               TEXT    NOT NULL,
                href              TEXT    NOT NULL
            );
            """;

    private static final String CREATE_ARCHIVE_ITEM_SOURCES_SQL = """
            CREATE TABLE IF NOT EXISTS archive_item_sources (
                id                BIGINT  PRIMARY KEY,
                archive_item_id   BIGINT  NOT NULL REFERENCES archive_items(id) ON DELETE CASCADE,
                rel               TEXT    NOT NULL,
                href              TEXT    NOT NULL,
                platform          TEXT,
                project_id        TEXT,
                project_slug      TEXT,
                file_id           TEXT
            );
            """;

    private static final String CREATE_MODS_SQL = """
            CREATE TABLE IF NOT EXISTS mods (
                mod_id        TEXT        PRIMARY KEY,
                names         TEXT[]      NOT NULL DEFAULT '{}',
                description   TEXT,
                license       TEXT,
                loaders       TEXT[]      NOT NULL DEFAULT '{}',
                mc_versions   TEXT[]      NOT NULL DEFAULT '{}',
                sides         TEXT[]      NOT NULL DEFAULT '{}',
                authors       TEXT[]      NOT NULL DEFAULT '{}',
                contributors  TEXT[]      NOT NULL DEFAULT '{}',
                credits       TEXT[]      NOT NULL DEFAULT '{}'
            );
            """;

    private static final String CREATE_MOD_VERSIONS_SQL = """
            CREATE TABLE IF NOT EXISTS mod_versions (
                id              BIGINT  PRIMARY KEY REFERENCES archive_items(id) ON DELETE CASCADE,
                mod_id          TEXT    NOT NULL REFERENCES mods(mod_id),
                version         TEXT    NOT NULL,
                description     TEXT,
                license         TEXT,
                names           TEXT[]  NOT NULL DEFAULT '{}',
                authors         TEXT[]  NOT NULL DEFAULT '{}',
                contributors    TEXT[]  NOT NULL DEFAULT '{}',
                credits         TEXT[]  NOT NULL DEFAULT '{}',
                side            TEXT    NOT NULL,
                UNIQUE (mod_id, version)
            );
            """;

    private static final String CREATE_MOD_VERSION_LOADER_META_SQL = """
            CREATE TABLE IF NOT EXISTS mod_version_loader_meta (
                id              BIGINT  PRIMARY KEY,
                mod_version_id  BIGINT  NOT NULL REFERENCES mod_versions(id) ON DELETE CASCADE,
                loader          TEXT    NOT NULL,
                api_version     TEXT,
                loader_version  TEXT,
                mc_version      TEXT    NOT NULL,
                meta_source     TEXT    NOT NULL
            );
            """;

    private static final String CREATE_MOD_VERSION_DEPENDENCIES_SQL = """
            CREATE TABLE IF NOT EXISTS mod_version_dependencies (
                id              BIGINT  PRIMARY KEY,
                mod_version_id  BIGINT  NOT NULL REFERENCES mod_versions(id) ON DELETE CASCADE,
                mod_id          TEXT    NOT NULL,
                version_range   TEXT,
                required        BOOLEAN NOT NULL
            );
            """;

    private static final String UPSERT_MOD_SQL = """
            INSERT INTO mods (mod_id, names, description, license, loaders, mc_versions, sides, authors, contributors, credits)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (mod_id) DO UPDATE SET
                names        = array_union_text(mods.names,        EXCLUDED.names),
                description  = EXCLUDED.description,
                license      = EXCLUDED.license,
                loaders      = array_union_text(mods.loaders,      EXCLUDED.loaders),
                mc_versions  = array_union_text(mods.mc_versions,  EXCLUDED.mc_versions),
                sides        = array_union_text(mods.sides,        EXCLUDED.sides),
                authors      = EXCLUDED.authors,
                contributors = EXCLUDED.contributors,
                credits      = EXCLUDED.credits
            """;

    private static final String INSERT_ARCHIVE_ITEM_SQL = """
            INSERT INTO archive_items (id, file_name, size, md5, sha1, sha256, sha512, related, archived_at, archived_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_ARCHIVE_ITEM_LINK_SQL = """
            INSERT INTO archive_item_links (id, archive_item_id, rel, href)
            VALUES (?, ?, ?, ?)
            """;

    private static final String INSERT_ARCHIVE_ITEM_SOURCE_SQL = """
            INSERT INTO archive_item_sources (id, archive_item_id, rel, href, platform, project_id, project_slug, file_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_MOD_VERSION_SQL = """
            INSERT INTO mod_versions (id, mod_id, version, description, license, names, authors, contributors, credits, side)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_MOD_VERSION_LOADER_META_SQL = """
            INSERT INTO mod_version_loader_meta (id, mod_version_id, loader, api_version, loader_version, mc_version, meta_source)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_MOD_VERSION_DEPENDENCY_SQL = """
            INSERT INTO mod_version_dependencies (id, mod_version_id, mod_id, version_range, required)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static void startup() {
        try (final var conn = ds.getConnection();
             final var stmt = conn.createStatement()) {
            stmt.execute(ARRAY_UNION_SQL);
            stmt.execute(CREATE_ARCHIVE_ITEMS_SQL);
            stmt.execute(CREATE_ARCHIVE_ITEM_LINKS_SQL);
            stmt.execute(CREATE_ARCHIVE_ITEM_SOURCES_SQL);
            stmt.execute(CREATE_MODS_SQL);
            stmt.execute(CREATE_MOD_VERSIONS_SQL);
            stmt.execute(CREATE_MOD_VERSION_LOADER_META_SQL);
            stmt.execute(CREATE_MOD_VERSION_DEPENDENCIES_SQL);

            JavaLibrary.startup(conn);

            System.out.println("Tables are ready.");
        } catch (final Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred while creating the tables.");
        }
    }

    private static ExtractResult extractMod(final long id, final @NonNull Path jarPath) throws IOException {
        try (final JarFile jar = new JarFile(jarPath.toFile())) {
            if (ForgeModExtractor.supports(jar)) return ForgeModExtractor.extract(id, jarPath);
            if (FMLManifestExtractor.supports(jar)) return FMLManifestExtractor.extract(id, jarPath);
            throw new IOException("No supported extractor found for: " + jarPath.getFileName());
        }
    }

    public static void ingest(final @NonNull Path jarPath) throws IOException {
        final long id = SnowflakeIdGenerator.next();
        final String fileName = jarPath.getFileName().toString();
        final Hashes hashes = HashUtil.hash(jarPath);
        final long archivedAt = Instant.now().toEpochMilli();

        // --- Extract parent mod metadata ---
        final ExtractResult result = extractMod(id, jarPath);

        final ModVersion modVersion = result.mod();
        final Collection<Link> links = result.links();

        // --- Detect and extract JiJ nested jars ---
        final List<JiJEntry> jijEntries = new ArrayList<>();
        final List<String> jijRelated = new ArrayList<>();
        final Path tempDir = Files.createTempDirectory("mca-jij-");

        final String s3Key = "mods/" + modVersion.modId() + "/" + modVersion.version() + "/" + fileName;

        try {
            try (final JarFile jar = new JarFile(jarPath.toFile())) {

                // Forge/NeoForge — META-INF/jarjar/manifest.json, fallback to JavaLibrary
                final JarEntry forgeManifest = (JarEntry) jar.getEntry("META-INF/jarjar/manifest.json");
                if (forgeManifest != null) {
                    try (final InputStream in = jar.getInputStream(forgeManifest)) {
                        final JsonObject manifest = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                        for (final JsonElement entry : manifest.getAsJsonArray("jars")) {
                            final JsonObject jijObj  = entry.getAsJsonObject();
                            final String jijPath     = jijObj.get("path").getAsString();
                            final JarEntry jijEntry  = (JarEntry) jar.getEntry(jijPath);
                            if (jijEntry == null) continue;

                            final Path tempFile = tempDir.resolve(Path.of(jijPath).getFileName().toString());
                            try (final InputStream jijIn = jar.getInputStream(jijEntry)) {
                                Files.write(tempFile, jijIn.readAllBytes());
                            }

                            final long jijId = SnowflakeIdGenerator.next();
                            final Hashes jijHashes = HashUtil.hash(tempFile);

                            try {
                                final ExtractResult jijResult = extractMod(jijId, tempFile);
                                jijEntries.add(new JiJEntry(jijId, tempFile, tempFile.getFileName().toString(), jijHashes, jijResult));
                                jijRelated.add(jijResult.mod().modId());
                            } catch (final IOException e) {
                                final String group      = jijObj.getAsJsonObject("identifier").get("group").getAsString();
                                final String artifact   = jijObj.getAsJsonObject("identifier").get("artifact").getAsString();
                                final String libVersion = jijObj.getAsJsonObject("version").get("artifactVersion").getAsString();
                                final String classifier = jijObj.has("classifier") ? jijObj.get("classifier").getAsString() : null;
                                final Source jijSource = new Source("jarInJar", s3Key, null, null, null, null);
                                JavaLibrary.ingest(tempFile, group, artifact, libVersion, classifier, List.of(jijSource));
                                jijRelated.add(group + ":" + artifact);
                            }
                        }
                    }
                }

                // Fabric — fabric.mod.json jars array, fail-fast
                final JarEntry fabricModJson = (JarEntry) jar.getEntry("fabric.mod.json");
                if (fabricModJson != null) {
                    try (final InputStream in = jar.getInputStream(fabricModJson)) {
                        final JsonObject fabricMeta = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                        if (fabricMeta.has("jars")) {
                            for (final JsonElement entry : fabricMeta.getAsJsonArray("jars")) {
                                final String jijPath    = entry.getAsJsonObject().get("file").getAsString();
                                final JarEntry jijEntry = (JarEntry) jar.getEntry(jijPath);
                                if (jijEntry == null) continue;

                                final Path tempFile = tempDir.resolve(Path.of(jijPath).getFileName().toString());
                                try (final InputStream jijIn = jar.getInputStream(jijEntry)) {
                                    Files.write(tempFile, jijIn.readAllBytes());
                                }

                                final long jijId = SnowflakeIdGenerator.next();
                                final Hashes jijHashes = HashUtil.hash(tempFile);
                                final ExtractResult jijResult = extractMod(jijId, tempFile);
                                jijEntries.add(new JiJEntry(jijId, tempFile, tempFile.getFileName().toString(), jijHashes, jijResult));
                                jijRelated.add(jijResult.mod().modId());
                            }
                        }
                    }
                }
            }

            // --- Build related ---
            final String[] related = Stream.concat(
                    modVersion.dependencies().stream().map(Dependency::modId),
                    jijRelated.stream()
            ).distinct().toArray(String[]::new);

            // --- DB writes ---
            try (final var conn = ds.getConnection()) {
                // --- archive_items (parent) ---
                try (final var ps = conn.prepareStatement(INSERT_ARCHIVE_ITEM_SQL)) {
                    ps.setLong(1, id);
                    ps.setString(2, fileName);
                    ps.setLong(3, hashes.size());
                    ps.setString(4, hashes.md5());
                    ps.setString(5, hashes.sha1());
                    ps.setString(6, hashes.sha256());
                    ps.setString(7, hashes.sha512());
                    ps.setArray(8, conn.createArrayOf("text", related));
                    ps.setLong(9, archivedAt);
                    ps.setString(10, ARCHIVE_INGEST_USER);
                    ps.executeUpdate();
                }

                // --- archive_item_links (parent) ---
                if (!links.isEmpty()) {
                    try (final var ps = conn.prepareStatement(INSERT_ARCHIVE_ITEM_LINK_SQL)) {
                        for (final Link link : links) {
                            ps.setLong(1, SnowflakeIdGenerator.next());
                            ps.setLong(2, id);
                            ps.setString(3, link.rel());
                            ps.setString(4, link.href());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }

                // --- mods upsert (parent) ---
                try (final var ps = conn.prepareStatement(UPSERT_MOD_SQL)) {
                    ps.setString(1, modVersion.modId());
                    ps.setArray(2, conn.createArrayOf("text", modVersion.names().toArray(new String[0])));
                    ps.setString(3, modVersion.description());
                    ps.setString(4, modVersion.license());
                    ps.setArray(5, conn.createArrayOf("text", modVersion.loaders().stream().map(ModLoaderMeta::loader).map(Enum::name).distinct().toArray(String[]::new)));
                    ps.setArray(6, conn.createArrayOf("text", modVersion.loaders().stream().map(ModLoaderMeta::mcVersion).distinct().toArray(String[]::new)));
                    ps.setArray(7, conn.createArrayOf("text", new String[]{modVersion.side().name()}));
                    ps.setArray(8, conn.createArrayOf("text", modVersion.authors().toArray(new String[0])));
                    ps.setArray(9, conn.createArrayOf("text", modVersion.contributors().toArray(new String[0])));
                    ps.setArray(10, conn.createArrayOf("text", modVersion.credits().toArray(new String[0])));
                    ps.executeUpdate();
                }

                // --- mod_versions (parent) ---
                try (final var ps = conn.prepareStatement(INSERT_MOD_VERSION_SQL)) {
                    ps.setLong(1, id);
                    ps.setString(2, modVersion.modId());
                    ps.setString(3, modVersion.version());
                    ps.setString(4, modVersion.description());
                    ps.setString(5, modVersion.license());
                    ps.setArray(6, conn.createArrayOf("text", modVersion.names().toArray(new String[0])));
                    ps.setArray(7, conn.createArrayOf("text", modVersion.authors().toArray(new String[0])));
                    ps.setArray(8, conn.createArrayOf("text", modVersion.contributors().toArray(new String[0])));
                    ps.setArray(9, conn.createArrayOf("text", modVersion.credits().toArray(new String[0])));
                    ps.setString(10, modVersion.side().name());
                    ps.executeUpdate();
                }

                // --- mod_version_loader_meta (parent) ---
                try (final var ps = conn.prepareStatement(INSERT_MOD_VERSION_LOADER_META_SQL)) {
                    for (final ModLoaderMeta meta : modVersion.loaders()) {
                        ps.setLong(1, SnowflakeIdGenerator.next());
                        ps.setLong(2, id);
                        ps.setString(3, meta.loader().name());
                        ps.setString(4, meta.apiVersion());
                        ps.setString(5, meta.loaderVersion());
                        ps.setString(6, meta.mcVersion());
                        ps.setString(7, meta.metaSource());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                // --- mod_version_dependencies (parent) ---
                if (!modVersion.dependencies().isEmpty()) {
                    try (final var ps = conn.prepareStatement(INSERT_MOD_VERSION_DEPENDENCY_SQL)) {
                        for (final var dep : modVersion.dependencies()) {
                            ps.setLong(1, SnowflakeIdGenerator.next());
                            ps.setLong(2, id);
                            ps.setString(3, dep.modId());
                            ps.setString(4, dep.versionRange());
                            ps.setBoolean(5, dep.required());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }

                // --- JiJ entries ---
                for (final JiJEntry jij : jijEntries) {
                    final ModVersion jijMod = jij.result().mod();
                    final Collection<Link> jijLinks = jij.result().links();
                    final String[] jijRelatedArr = jijMod.dependencies().stream().map(Dependency::modId).distinct().toArray(String[]::new);

                    // archive_items
                    try (final var ps = conn.prepareStatement(INSERT_ARCHIVE_ITEM_SQL)) {
                        ps.setLong(1, jij.id());
                        ps.setString(2, jij.fileName());
                        ps.setLong(3, jij.hashes().size());
                        ps.setString(4, jij.hashes().md5());
                        ps.setString(5, jij.hashes().sha1());
                        ps.setString(6, jij.hashes().sha256());
                        ps.setString(7, jij.hashes().sha512());
                        ps.setArray(8, conn.createArrayOf("text", jijRelatedArr));
                        ps.setLong(9, archivedAt);
                        ps.setString(10, null);
                        ps.executeUpdate();
                    }

                    // archive_item_links
                    if (!jijLinks.isEmpty()) {
                        try (final var ps = conn.prepareStatement(INSERT_ARCHIVE_ITEM_LINK_SQL)) {
                            for (final Link link : jijLinks) {
                                ps.setLong(1, SnowflakeIdGenerator.next());
                                ps.setLong(2, jij.id());
                                ps.setString(3, link.rel());
                                ps.setString(4, link.href());
                                ps.addBatch();
                            }
                            ps.executeBatch();
                        }
                    }

                    // archive_item_sources — jarInJar source pointing to parent S3 key
                    try (final var ps = conn.prepareStatement(INSERT_ARCHIVE_ITEM_SOURCE_SQL)) {
                        ps.setLong(1, SnowflakeIdGenerator.next());
                        ps.setLong(2, jij.id());
                        ps.setString(3, "jarInJar");
                        ps.setString(4, s3Key);
                        ps.setString(5, null);
                        ps.setString(6, null);
                        ps.setString(7, null);
                        ps.setString(8, null);
                        ps.executeUpdate();
                    }

                    // mods upsert
                    try (final var ps = conn.prepareStatement(UPSERT_MOD_SQL)) {
                        ps.setString(1, jijMod.modId());
                        ps.setArray(2, conn.createArrayOf("text", jijMod.names().toArray(new String[0])));
                        ps.setString(3, jijMod.description());
                        ps.setString(4, jijMod.license());
                        ps.setArray(5, conn.createArrayOf("text", jijMod.loaders().stream().map(ModLoaderMeta::loader).map(Enum::name).distinct().toArray(String[]::new)));
                        ps.setArray(6, conn.createArrayOf("text", jijMod.loaders().stream().map(ModLoaderMeta::mcVersion).distinct().toArray(String[]::new)));
                        ps.setArray(7, conn.createArrayOf("text", new String[]{jijMod.side().name()}));
                        ps.setArray(8, conn.createArrayOf("text", jijMod.authors().toArray(new String[0])));
                        ps.setArray(9, conn.createArrayOf("text", jijMod.contributors().toArray(new String[0])));
                        ps.setArray(10, conn.createArrayOf("text", jijMod.credits().toArray(new String[0])));
                        ps.executeUpdate();
                    }

                    // mod_versions
                    try (final var ps = conn.prepareStatement(INSERT_MOD_VERSION_SQL)) {
                        ps.setLong(1, jij.id());
                        ps.setString(2, jijMod.modId());
                        ps.setString(3, jijMod.version());
                        ps.setString(4, jijMod.description());
                        ps.setString(5, jijMod.license());
                        ps.setArray(6, conn.createArrayOf("text", jijMod.names().toArray(new String[0])));
                        ps.setArray(7, conn.createArrayOf("text", jijMod.authors().toArray(new String[0])));
                        ps.setArray(8, conn.createArrayOf("text", jijMod.contributors().toArray(new String[0])));
                        ps.setArray(9, conn.createArrayOf("text", jijMod.credits().toArray(new String[0])));
                        ps.setString(10, jijMod.side().name());
                        ps.executeUpdate();
                    }

                    // mod_version_loader_meta
                    try (final var ps = conn.prepareStatement(INSERT_MOD_VERSION_LOADER_META_SQL)) {
                        for (final ModLoaderMeta meta : jijMod.loaders()) {
                            ps.setLong(1, SnowflakeIdGenerator.next());
                            ps.setLong(2, jij.id());
                            ps.setString(3, meta.loader().name());
                            ps.setString(4, meta.apiVersion());
                            ps.setString(5, meta.loaderVersion());
                            ps.setString(6, meta.mcVersion());
                            ps.setString(7, meta.metaSource());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }

                    // mod_version_dependencies
                    if (!jijMod.dependencies().isEmpty()) {
                        try (final var ps = conn.prepareStatement(INSERT_MOD_VERSION_DEPENDENCY_SQL)) {
                            for (final var dep : jijMod.dependencies()) {
                                ps.setLong(1, SnowflakeIdGenerator.next());
                                ps.setLong(2, jij.id());
                                ps.setString(3, dep.modId());
                                ps.setString(4, dep.versionRange());
                                ps.setBoolean(5, dep.required());
                                ps.addBatch();
                            }
                            ps.executeBatch();
                        }
                    }
                }
            } catch (final Exception e) {
                //noinspection CallToPrintStackTrace
                e.printStackTrace();
                System.out.println("An error occurred while ingesting: " + fileName);
                throw new IOException("DB ingest failed for: " + fileName, e);
            }

            // --- S3 uploads (after all DB writes succeed) ---
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(S3_BUCKET)
                            .key(s3Key)
                            .contentType("application/java-archive")
                            .contentLength(hashes.size())
                            .build(),
                    RequestBody.fromFile(jarPath)
            );

            for (final JiJEntry jij : jijEntries) {
                final ModVersion jijMod = jij.result().mod();
                final String jijS3Key = "mods/" + jijMod.modId() + "/" + jijMod.version() + "/" + jij.fileName();
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket(S3_BUCKET)
                                .key(jijS3Key)
                                .contentType("application/java-archive")
                                .contentLength(jij.hashes().size())
                                .build(),
                        RequestBody.fromFile(jij.tempFile())
                );
            }

            System.out.println("Ingested: " + fileName + " (" + modVersion.modId() + " v" + modVersion.version() + ")");

        } finally {
            // --- Clean up temp files ---
            for (final JiJEntry jij : jijEntries) {
                Files.deleteIfExists(jij.tempFile());
            }
            Files.deleteIfExists(tempDir);
        }
    }

    private record JiJEntry(
            long id,
            @NonNull Path tempFile,
            @NonNull String fileName,
            @NonNull Hashes hashes,
            @NonNull ExtractResult result
    ) {}

    public static void ingestDir(
            final @NonNull Path inputDir,
            final @NonNull Path doneDir,
            final @NonNull Path failedDir) {
        try (final var stream = Files.list(inputDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .forEach(jarPath -> {
                        try {
                            ingest(jarPath);
                            Files.move(jarPath, doneDir.resolve(jarPath.getFileName()));
                        } catch (final Exception e) {
                            //noinspection CallToPrintStackTrace
                            e.printStackTrace();
                            try {
                                Files.move(jarPath, failedDir.resolve(jarPath.getFileName()));
                            } catch (final IOException moveEx) {
                                //noinspection CallToPrintStackTrace
                                moveEx.printStackTrace();
                            }
                        }
                    });
        } catch (final Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred while processing the input directory: " + inputDir);
        }
    }
}
