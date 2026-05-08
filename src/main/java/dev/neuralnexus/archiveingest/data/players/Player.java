package dev.neuralnexus.archiveingest.data.players;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;

public record Player(
        @NonNull String id,
        @NonNull String name,
        @Nullable Boolean legacy,
        @Nullable Boolean demo,
        long timestamp,
        @Nullable JsonObject[] profileActions,
        Property @Nullable [] properties) {
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

    public static final Gson GSON = new GsonBuilder()
            .setLenient()
            .registerTypeAdapter(Player.class, new Player.Deserializer())
            .registerTypeAdapter(TextureData.class, new TextureData.Deserializer())
            .registerTypeAdapter(SkinTexture.class, new SkinTexture.Deserializer())
            .registerTypeAdapter(CapeTexture.class, new CapeTexture.Deserializer())
            .create();

    private static final String TEXTURE_BASE_URL = "http://textures.minecraft.net/texture/";

    public static void processPlayer(
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
        playerStmt.setString(5, GSON.toJson(player.profileActions()));
        playerStmt.setLong(6, firstSeen);
        playerStmt.setLong(7, lastSeen);
        playerStmt.addBatch();
    }

    public static class Deserializer implements JsonDeserializer<Player> {
        private static final Set<String> KNOWN_FIELDS = Set.of("id", "name", "legacy", "demo", "properties", "profileActions");
        private static final Gson delegate = new GsonBuilder().setLenient().create();

        @Override
        public Player deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) throws JsonParseException {
            final JsonObject obj = json.getAsJsonObject();
            for (final String key : obj.keySet()) {
                if (!KNOWN_FIELDS.contains(key)) {
                    throw new JsonParseException("Unknown field '" + key + "' encountered");
                }
            }
            return delegate.fromJson(obj, Player.class);
        }
    }
}
