package dev.neuralnexus.archiveingest.data.players;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.FileReader;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class IngestJSONL {
    private static final Gson gson = new GsonBuilder().setLenient().create();

    private static final DataSource ds;

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
        ds = new HikariDataSource(config);

        startup();
    }

    private static void startup() {
        final String createSkinsSQL = """
        CREATE TABLE IF NOT EXISTS skins (
            id INTEGER PRIMARY KEY,
            hash TEXT NOT NULL UNIQUE,
            model TEXT
        );
    """;
        final String createCapesSQL = """
        CREATE TABLE IF NOT EXISTS capes (
            id INTEGER PRIMARY KEY,
            hash TEXT NOT NULL UNIQUE
        );
    """;
        final String createPlayersSQL = """
        CREATE TABLE IF NOT EXISTS players (
            id UUID PRIMARY KEY,
            name TEXT NOT NULL,
            profile_actions JSONB
        );
    """;
        final String createPlayerTexturesSQL = """
        CREATE TABLE IF NOT EXISTS player_textures (
            player_id UUID REFERENCES players(id),
            skin_id INTEGER REFERENCES skins(id),
            cape_id INTEGER REFERENCES capes(id),
            timestamp BIGINT NOT NULL,
            PRIMARY KEY (player_id, timestamp)
        );
    """;

        try (final var conn = ds.getConnection();
             final var stmt = conn.createStatement()) {
            stmt.execute(createSkinsSQL);
            stmt.execute(createCapesSQL);
            stmt.execute(createPlayersSQL);
            stmt.execute(createPlayerTexturesSQL);
            System.out.println("Tables are ready.");
        } catch (final Exception e) {
            e.printStackTrace();
            System.out.println("An error occurred while creating the tables.");
        }
    }

    private static final String TEXTURE_BASE_URL = "http://textures.minecraft.net/texture/";

    public static void ingest(final @NonNull String filePath) {
        final String insertPlayerSQL =
                "INSERT INTO players (id, name, profile_actions) VALUES (?::uuid, ?, ?::jsonb) ON CONFLICT (id) DO NOTHING";
        final String insertSkinSQL = """
            INSERT INTO skins (id, hash, model)
            SELECT COALESCE(MAX(id), 0) + 1, ?, ? FROM skins
            ON CONFLICT (hash) DO UPDATE SET hash = EXCLUDED.hash
            RETURNING id
            """;
        final String insertCapeSQL = """
            INSERT INTO capes (id, hash)
            SELECT COALESCE(MAX(id), 0) + 1, ? FROM capes
            ON CONFLICT (hash) DO UPDATE SET hash = EXCLUDED.hash
            RETURNING id
            """;
        final String insertPlayerTextureSQL =
                "INSERT INTO player_textures (player_id, skin_id, cape_id, timestamp) VALUES (?::uuid, ?, ?, ?) ON CONFLICT (player_id, timestamp) DO NOTHING";
        final int BATCH_SIZE = 1000;

        final Object2IntOpenHashMap<String> skinCache = new Object2IntOpenHashMap<>();
        final Object2IntOpenHashMap<String> capeCache = new Object2IntOpenHashMap<>();

        try (final BufferedReader reader = new BufferedReader(new FileReader(filePath));
             final var conn = ds.getConnection();
             final var playerStmt = conn.prepareStatement(insertPlayerSQL);
             final var playerTextureStmt = conn.prepareStatement(insertPlayerTextureSQL)) {

            conn.setAutoCommit(false);

            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                final Player player = gson.fromJson(line, Player.class);

                playerStmt.setString(1, player.id());
                playerStmt.setString(2, player.name());
                playerStmt.setString(3, gson.toJson(player.profileActions()));
                playerStmt.addBatch();

                if (player.properties() != null) {
                    for (final Property property : player.properties()) {
                        if (!property.name().equals("textures")) continue;

                        final String decoded = new String(Base64.getDecoder().decode(property.value()));
                        final TextureData textureData = gson.fromJson(decoded, TextureData.class);

                        Integer skinId = null;
                        Integer capeId = null;

                        if (textureData.textures().SKIN() != null) {
                            final String skinHash = textureData.textures().SKIN().url().replace(TEXTURE_BASE_URL, "");
                            final String model = textureData.textures().SKIN().metadata() != null
                                    ? textureData.textures().SKIN().metadata().model()
                                    : null;
                            if (skinCache.containsKey(skinHash)) {
                                skinId = skinCache.getInt(skinHash);
                            } else {
                                try (final var stmt = conn.prepareStatement(insertSkinSQL)) {
                                    stmt.setString(1, skinHash);
                                    stmt.setString(2, model);
                                    final var rs = stmt.executeQuery();
                                    if (rs.next()) skinId = rs.getInt(1);
                                }
                                if (skinId != null) skinCache.put(skinHash, (int) skinId);
                            }
                        }

                        if (textureData.textures().CAPE() != null) {
                            final String capeHash = textureData.textures().CAPE().url().replace(TEXTURE_BASE_URL, "");
                            if (capeCache.containsKey(capeHash)) {
                                capeId = capeCache.getInt(capeHash);
                            } else {
                                try (final var stmt = conn.prepareStatement(insertCapeSQL)) {
                                    stmt.setString(1, capeHash);
                                    final var rs = stmt.executeQuery();
                                    if (rs.next()) capeId = rs.getInt(1);
                                }
                                if (capeId != null) capeCache.put(capeHash, (int) capeId);
                            }
                        }

                        playerTextureStmt.setString(1, player.id());
                        playerTextureStmt.setObject(2, skinId);
                        playerTextureStmt.setObject(3, capeId);
                        playerTextureStmt.setLong(4, textureData.timestamp());
                        playerTextureStmt.addBatch();
                    }
                }

                if (++count % BATCH_SIZE == 0) {
                    playerStmt.executeBatch();
                    playerTextureStmt.executeBatch();
                    conn.commit();
                }
            }

            // Flush remaining
            playerStmt.executeBatch();
            playerTextureStmt.executeBatch();
            conn.commit();

            System.out.println("Ingestion completed successfully. Rows processed: " + count);
        } catch (final Exception e) {
            e.printStackTrace();
            System.out.println("An error occurred during ingestion.");
        }
    }

    // All Skins and Capes are prepended by: "http://textures.minecraft.net/texture/"
    public record Skin(int id, @NonNull String hash, @Nullable String model) {}

    public record Cape(int id, @NonNull String url) {}

    public record Player(
            @NonNull String id,
            @NonNull String name,
            @NonNull JsonObject[] profileActions,
            Property@Nullable[] properties) {}

    public record PlayerTexture(
            @NonNull String playerId,
            int skinId,
            int capeId,
            long timestamp) {}

    public record Property(@NonNull String name, @NonNull String value) {}

    public record TextureData(
            long timestamp,
            @NonNull String profileId,
            @NonNull String profileName,
            @NonNull Textures textures) {}

    public record Textures(
            @Nullable SkinTexture SKIN,
            @Nullable CapeTexture CAPE) {}

    public record SkinTexture(
            @NonNull String url,
            @Nullable SkinMetadata metadata) {}

    public record SkinMetadata(@Nullable String model) {}

    public record CapeTexture(@NonNull String url) {}
}
