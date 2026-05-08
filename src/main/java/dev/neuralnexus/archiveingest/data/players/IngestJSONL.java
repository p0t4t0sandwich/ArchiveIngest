package dev.neuralnexus.archiveingest.data.players;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.FileReader;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;

/**
 * Primarily for ingesting mojang.jsonl from: <br>
 * minecraft-uuids-2024-02-02 <br>
 * minecraft-uuids-2024-02-22 <br>
 * minecraft-uuids-2029-09-01
 */
public class IngestJSONL {
    private static final Gson gson = new GsonBuilder()
            .setLenient()
            .registerTypeAdapter(Player.class, new Player.Deserializer())
            .registerTypeAdapter(TextureData.class, new TextureData.Deserializer())
            .registerTypeAdapter(SkinTexture.class, new SkinTexture.Deserializer())
            .registerTypeAdapter(CapeTexture.class, new CapeTexture.Deserializer())
            .create();

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
        final String createPlayersSQL = """
        CREATE TABLE IF NOT EXISTS players (
            id UUID PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            legacy BOOLEAN,
            demo BOOLEAN,
            profile_actions JSONB,
            first_seen BIGINT NOT NULL,
            last_seen BIGINT NOT NULL
        );
    """;
        final String createPlayerTexturesSQL = """
        CREATE TABLE IF NOT EXISTS player_textures (
            player_id UUID REFERENCES players(id),
            skin TEXT REFERENCES textures(hash),
            model TEXT,
            cape TEXT REFERENCES textures(hash),
            first_seen BIGINT NOT NULL,
            last_seen BIGINT NOT NULL
        );
    """;
        final String createPlayerNamesSQL = """
        CREATE TABLE IF NOT EXISTS player_names (
            player_id UUID REFERENCES players(id),
            name TEXT NOT NULL,
            first_seen BIGINT NOT NULL,
            last_seen BIGINT NOT NULL,
            PRIMARY KEY (player_id, name)
        );
    """;

        try (final var conn = ds.getConnection();
             final var stmt = conn.createStatement()) {
            stmt.execute(createTexturesSQL);
            stmt.execute(createPlayersSQL);
            stmt.execute(createPlayerTexturesSQL);
            stmt.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS player_textures_unique
                ON player_textures (player_id, COALESCE(skin, ''), COALESCE(model, ''), COALESCE(cape, ''))
                """);
            stmt.execute(createPlayerNamesSQL);
            System.out.println("Tables are ready.");
        } catch (final Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred while creating the tables.");
        }
    }

    private static final String TEXTURE_BASE_URL = "http://textures.minecraft.net/texture/";

    private static final String INSERT_PLAYER_SQL = """
        INSERT INTO players (id, name, legacy, demo, profile_actions, first_seen, last_seen) VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?, ?)
        ON CONFLICT (id) DO UPDATE SET
            name = CASE WHEN EXCLUDED.last_seen >= players.last_seen THEN EXCLUDED.name ELSE players.name END,
            legacy = COALESCE(EXCLUDED.legacy, players.legacy),
            demo = COALESCE(EXCLUDED.demo, players.demo),
            profile_actions = CASE WHEN EXCLUDED.last_seen >= players.last_seen THEN EXCLUDED.profile_actions ELSE players.profile_actions END,
            first_seen = LEAST(EXCLUDED.first_seen, players.first_seen),
            last_seen = GREATEST(EXCLUDED.last_seen, players.last_seen)
        """;

    private static final String INSERT_TEXTURE_SQL = """
        INSERT INTO textures (hash) VALUES (?)
        ON CONFLICT (hash) DO NOTHING
        """;

    private static final String INSERT_PLAYER_TEXTURE_SQL = """
        INSERT INTO player_textures (player_id, skin, model, cape, first_seen, last_seen)
        VALUES (?::uuid, ?, ?, ?, ?, ?)
        ON CONFLICT (player_id, COALESCE(skin, ''), COALESCE(model, ''), COALESCE(cape, '')) DO UPDATE SET
            first_seen = LEAST(EXCLUDED.first_seen, player_textures.first_seen),
            last_seen = GREATEST(EXCLUDED.last_seen, player_textures.last_seen)
        """;

    private static final String INSERT_PLAYER_NAME_SQL = """
        INSERT INTO player_names (player_id, name, first_seen, last_seen) VALUES (?::uuid, ?, ?, ?)
        ON CONFLICT (player_id, name) DO UPDATE SET
            first_seen = LEAST(EXCLUDED.first_seen, player_names.first_seen),
            last_seen = GREATEST(EXCLUDED.last_seen, player_names.last_seen)
        """;

    private static void processPlayer(
            final @NonNull Player player,
            final @Nullable TextureData textureData,
            final @NonNull PreparedStatement playerStmt,
            final @NonNull PreparedStatement textureStmt,
            final @NonNull PreparedStatement playerTextureStmt,
            final @NonNull PreparedStatement playerNameStmt) throws SQLException {
        long lastSeen = player.timestamp();
        long firstSeen = player.timestamp();

        if (textureData != null) {
            lastSeen = Math.max(lastSeen, textureData.timestamp());
            firstSeen = Math.min(firstSeen, textureData.timestamp());
            if (firstSeen == 0L) firstSeen = textureData.timestamp();

            final String skinHash = textureData.textures().SKIN() != null
                    ? textureData.textures().SKIN().url().replace(TEXTURE_BASE_URL, "")
                    : null;
            final String model = textureData.textures().SKIN() != null && textureData.textures().SKIN().metadata() != null
                    ? textureData.textures().SKIN().metadata().model()
                    : null;
            final String capeHash = textureData.textures().CAPE() != null
                    ? textureData.textures().CAPE().url().replace(TEXTURE_BASE_URL, "")
                    : null;

            if (skinHash != null) {
                textureStmt.setString(1, skinHash);
                textureStmt.addBatch();
            }

            if (capeHash != null) {
                textureStmt.setString(1, capeHash);
                textureStmt.addBatch();
            }

            playerTextureStmt.setString(1, player.id());
            playerTextureStmt.setString(2, skinHash);
            playerTextureStmt.setString(3, model);
            playerTextureStmt.setString(4, capeHash);
            playerTextureStmt.setLong(5, firstSeen);
            playerTextureStmt.setLong(6, lastSeen);
            playerTextureStmt.addBatch();
        }
        if (firstSeen == 0L) firstSeen = lastSeen;

        playerNameStmt.setString(1, player.id());
        playerNameStmt.setString(2, player.name());
        playerNameStmt.setLong(3, firstSeen);
        playerNameStmt.setLong(4, lastSeen);
        playerNameStmt.addBatch();

        playerStmt.setString(1, player.id());
        playerStmt.setString(2, player.name());
        playerStmt.setObject(3, player.legacy());
        playerStmt.setObject(4, player.demo());
        playerStmt.setString(5, gson.toJson(player.profileActions()));
        playerStmt.setLong(6, firstSeen);
        playerStmt.setLong(7, lastSeen);
        playerStmt.addBatch();
    }

    public static void ingest(final @NonNull String filePath) {
        final int BATCH_SIZE = 50000;
        final long startTime = System.currentTimeMillis();

        try (final BufferedReader reader = new BufferedReader(new FileReader(filePath));
             final var conn = ds.getConnection();
             final var playerStmt = conn.prepareStatement(INSERT_PLAYER_SQL);
             final var textureStmt = conn.prepareStatement(INSERT_TEXTURE_SQL);
             final var playerTextureStmt = conn.prepareStatement(INSERT_PLAYER_TEXTURE_SQL);
             final var playerNameStmt = conn.prepareStatement(INSERT_PLAYER_NAME_SQL)) {

            conn.setAutoCommit(false);

            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                final Player player = gson.fromJson(line, Player.class);

                TextureData textureData = null;
                if (player.properties() != null) {
                    for (final Property property : player.properties()) {
                        if (!property.name().equals("textures")) {
                            throw new IllegalStateException("Unknown property '" + property.name() + "' encountered for player: " + player.id());
                        }
                        final String decoded = new String(Base64.getDecoder().decode(property.value()));
                        textureData = gson.fromJson(decoded, TextureData.class);
                    }
                }

                processPlayer(player, textureData, playerStmt, textureStmt, playerTextureStmt, playerNameStmt);

                if (++count % BATCH_SIZE == 0) {
                    textureStmt.executeBatch();
                    playerStmt.executeBatch();
                    playerTextureStmt.executeBatch();
                    playerNameStmt.executeBatch();
                    conn.commit();
                    final long elapsed = System.currentTimeMillis() - startTime;
                    System.out.printf("Rows processed: %d | Elapsed: %ds | Rate: %d rows/s%n",
                            count, elapsed / 1000, count / Math.max(1, elapsed / 1000));
                }
            }

            // Flush remaining
            textureStmt.executeBatch();
            playerStmt.executeBatch();
            playerTextureStmt.executeBatch();
            playerNameStmt.executeBatch();
            conn.commit();

            final long elapsed = System.currentTimeMillis() - startTime;
            System.out.printf("Ingestion completed successfully. Rows processed: %d | Elapsed: %ds | Rate: %d rows/s%n",
                    count, elapsed / 1000, count / Math.max(1, elapsed / 1000));
        } catch (final Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred during ingestion.");
        }
    }
}
