package dev.neuralnexus.archiveingest.data.mca.libraries;

import dev.neuralnexus.archiveingest.data.HashUtil;
import dev.neuralnexus.archiveingest.data.HashUtil.Hashes;
import dev.neuralnexus.archiveingest.data.SnowflakeIdGenerator;
import dev.neuralnexus.archiveingest.data.mca.Source;
import dev.neuralnexus.archiveingest.data.mca.mods.ModArchive;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.Collection;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static dev.neuralnexus.archiveingest.data.mca.mods.ModArchive.ARCHIVE_INGEST_USER;

public record JavaLibrary(
        long id,
        @NonNull String group,
        @NonNull String artifact,
        @NonNull String version,
        @Nullable String classifier,
        @Nullable String name,  // Implementation-Title / Specification-Title
        @Nullable String vendor // Implementation-Vendor / Specification-Vendor
) {
    private static final String CREATE_JAVA_LIBRARIES_SQL = """
        CREATE TABLE IF NOT EXISTS java_libraries (
            id          BIGINT  PRIMARY KEY REFERENCES archive_items(id) ON DELETE CASCADE,
            group_id    TEXT    NOT NULL,
            artifact_id TEXT    NOT NULL,
            version     TEXT    NOT NULL,
            classifier  TEXT,
            name        TEXT,
            vendor      TEXT
        );
        """;

    private static final String INSERT_JAVA_LIBRARY_SQL = """
        INSERT INTO java_libraries (id, group_id, artifact_id, version, classifier, name, vendor)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String INSERT_ARCHIVE_ITEM_SQL = """
        INSERT INTO archive_items (id, file_name, size, md5, sha1, sha256, sha512, related, archived_at, archived_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String INSERT_ARCHIVE_ITEM_SOURCE_SQL = """
        INSERT INTO archive_item_sources (id, archive_item_id, rel, href, platform, project_id, project_slug, file_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

    public static void startup(final @NonNull Connection conn) throws Exception {
        try (final var stmt = conn.createStatement()) {
            stmt.execute(CREATE_JAVA_LIBRARIES_SQL);
        }
    }

    public static @NonNull JavaLibrary ingest(
            final @NonNull Path jarPath,
            final @NonNull String group,
            final @NonNull String artifact,
            final @NonNull String version,
            final @Nullable String classifier,
            final Collection<Source> sources
    ) throws IOException {
        final long id = SnowflakeIdGenerator.next();
        final String fileName = jarPath.getFileName().toString();
        final Hashes hashes = HashUtil.hash(jarPath);
        final long archivedAt = Instant.now().toEpochMilli();
        final String s3Key = "libs/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + fileName;

        // --- Parse manifest for supplementary metadata ---
        final String name;
        final String vendor;
        try (final JarFile jar = new JarFile(jarPath.toFile())) {
            final Manifest manifest = jar.getManifest();
            final Attributes attrs = manifest != null
                    ? manifest.getMainAttributes()
                    : new Attributes();

            name = attrs.getValue("Implementation-Title") != null
                    ? attrs.getValue("Implementation-Title")
                    : attrs.getValue("Specification-Title");
            vendor = attrs.getValue("Implementation-Vendor") != null
                    ? attrs.getValue("Implementation-Vendor")
                    : attrs.getValue("Specification-Vendor");
        }

        final String mavenCoord = group + ":" + artifact;

        try (final var conn = ModArchive.ds.getConnection()) {
            // --- archive_items ---
            try (final var ps = conn.prepareStatement(INSERT_ARCHIVE_ITEM_SQL)) {
                ps.setLong(1, id);
                ps.setString(2, fileName);
                ps.setLong(3, hashes.size());
                ps.setString(4, hashes.md5());
                ps.setString(5, hashes.sha1());
                ps.setString(6, hashes.sha256());
                ps.setString(7, hashes.sha512());
                ps.setArray(8, conn.createArrayOf("text", new String[]{mavenCoord}));
                ps.setLong(9, archivedAt);
                ps.setString(10, ARCHIVE_INGEST_USER);
                ps.executeUpdate();
            }

            // --- archive_item_sources ---
            for (final Source src : sources) {
                try (final var ps = conn.prepareStatement(INSERT_ARCHIVE_ITEM_SOURCE_SQL)) {
                    ps.setLong(1, SnowflakeIdGenerator.next());
                    ps.setLong(2, id);
                    ps.setString(3, src.rel());
                    ps.setString(4, src.href());
                    ps.setString(5, src.platform());
                    ps.setString(6, src.projectId());
                    ps.setString(7, src.projectSlug());
                    ps.setString(8, src.fileId());
                    ps.executeUpdate();
                }
            }

            // --- java_libraries ---
            try (final var ps = conn.prepareStatement(INSERT_JAVA_LIBRARY_SQL)) {
                ps.setLong(1, id);
                ps.setString(2, group);
                ps.setString(3, artifact);
                ps.setString(4, version);
                ps.setString(5, classifier);
                ps.setString(6, name);
                ps.setString(7, vendor);
                ps.executeUpdate();
            }
        } catch (final Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred while ingesting library: " + fileName);
            throw new IOException("DB ingest failed for library: " + fileName, e);
        }

        // --- S3 upload ---
        ModArchive.s3.putObject(
                PutObjectRequest.builder()
                        .bucket(ModArchive.S3_BUCKET)
                        .key(s3Key)
                        .contentType("application/java-archive")
                        .contentLength(hashes.size())
                        .build(),
                RequestBody.fromFile(jarPath)
        );

        System.out.println("Ingested library: " + mavenCoord + ":" + version);

        return new JavaLibrary(id, group, artifact, version, classifier, name, vendor);
    }
}
