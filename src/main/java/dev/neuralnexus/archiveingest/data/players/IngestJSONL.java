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

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

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
            id UUID PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            legacy BOOLEAN,
            demo BOOLEAN,
            profile_actions JSONB,
            last_updated BIGINT NOT NULL
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
        final String createPlayerNamesSQL = """
        CREATE TABLE IF NOT EXISTS player_names (
            player_id UUID REFERENCES players(id),
            name TEXT NOT NULL,
            timestamp BIGINT NOT NULL,
            PRIMARY KEY (player_id, name, timestamp)
        );
    """;

        try (final var conn = ds.getConnection();
             final var stmt = conn.createStatement()) {
            stmt.execute(createSkinsSQL);
            stmt.execute(createCapesSQL);
            stmt.execute(createPlayersSQL);
            stmt.execute(createPlayerTexturesSQL);
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
        INSERT INTO players (id, name, legacy, demo, profile_actions, last_updated) VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?)
        ON CONFLICT (id) DO UPDATE SET
            name = CASE WHEN EXCLUDED.last_updated >= players.last_updated THEN EXCLUDED.name ELSE players.name END,
            legacy = COALESCE(EXCLUDED.legacy, players.legacy),
            demo = COALESCE(EXCLUDED.demo, players.demo),
            profile_actions = CASE WHEN EXCLUDED.last_updated >= players.last_updated THEN EXCLUDED.profile_actions ELSE players.profile_actions END,
            last_updated = GREATEST(EXCLUDED.last_updated, players.last_updated)
        """;

    private static final String INSERT_SKIN_SQL = """
        INSERT INTO skins (id, hash, model)
        SELECT COALESCE(MAX(id), 0) + 1, ?, ? FROM skins
        ON CONFLICT (hash) DO UPDATE SET hash = EXCLUDED.hash
        RETURNING id
        """;
    private static final String INSERT_CAPE_SQL = """
        INSERT INTO capes (id, hash)
        SELECT COALESCE(MAX(id), 0) + 1, ? FROM capes
        ON CONFLICT (hash) DO UPDATE SET hash = EXCLUDED.hash
        RETURNING id
        """;
    private static final String INSERT_PLAYER_TEXTURE_SQL =
            "INSERT INTO player_textures (player_id, skin_id, cape_id, timestamp) VALUES (?::uuid, ?, ?, ?) ON CONFLICT (player_id, timestamp) DO NOTHING";
    private static final String INSERT_PLAYER_NAME_SQL =
            "INSERT INTO player_names (player_id, name, timestamp) VALUES (?::uuid, ?, ?) ON CONFLICT (player_id, name, timestamp) DO NOTHING";

    private static Integer upsertSkin(
            final @NonNull Connection conn,
            final @NonNull Object2IntOpenHashMap<String> skinCache,
            final @NonNull String skinHash,
            final @Nullable String model) throws SQLException {
        if (skinCache.containsKey(skinHash)) return skinCache.getInt(skinHash);
        try (final var stmt = conn.prepareStatement(INSERT_SKIN_SQL)) {
            stmt.setString(1, skinHash);
            stmt.setString(2, model);
            final var rs = stmt.executeQuery();
            if (rs.next()) {
                final int id = rs.getInt(1);
                skinCache.put(skinHash, id);
                return id;
            }
        }
        return null;
    }

    private static Integer upsertCape(
            final @NonNull Connection conn,
            final @NonNull Object2IntOpenHashMap<String> capeCache,
            final @NonNull String capeHash) throws SQLException {
        if (capeCache.containsKey(capeHash)) return capeCache.getInt(capeHash);
        try (final var stmt = conn.prepareStatement(INSERT_CAPE_SQL)) {
            stmt.setString(1, capeHash);
            final var rs = stmt.executeQuery();
            if (rs.next()) {
                final int id = rs.getInt(1);
                capeCache.put(capeHash, id);
                return id;
            }
        }
        return null;
    }

    private static void processPlayer(
            final @NonNull ParsedPlayer parsedPlayer,
            final @NonNull PreparedStatement playerStmt,
            final @NonNull PreparedStatement playerTextureStmt,
            final @NonNull PreparedStatement playerNameStmt,
            final @NonNull Connection conn,
            final @NonNull Object2IntOpenHashMap<String> skinCache,
            final @NonNull Object2IntOpenHashMap<String> capeCache) throws SQLException {

        final Player player = parsedPlayer.player();
        final TextureData textureData = parsedPlayer.textureData();

        long lastUpdated = player.timestamp();

        if (textureData != null) {
            lastUpdated = Math.max(lastUpdated, textureData.timestamp());

            Integer skinId = null;
            Integer capeId = null;

            if (textureData.textures().SKIN() != null) {
                final String skinHash = textureData.textures().SKIN().url().replace(TEXTURE_BASE_URL, "");
                final String model = textureData.textures().SKIN().metadata() != null
                        ? textureData.textures().SKIN().metadata().model()
                        : null;
                skinId = upsertSkin(conn, skinCache, skinHash, model);
            }

            if (textureData.textures().CAPE() != null) {
                final String capeHash = textureData.textures().CAPE().url().replace(TEXTURE_BASE_URL, "");
                capeId = upsertCape(conn, capeCache, capeHash);
            }

            playerTextureStmt.setString(1, player.id());
            playerTextureStmt.setObject(2, skinId);
            playerTextureStmt.setObject(3, capeId);
            playerTextureStmt.setLong(4, textureData.timestamp());
            playerTextureStmt.addBatch();

            playerNameStmt.setString(1, player.id());
            playerNameStmt.setString(2, player.name());
            playerNameStmt.setLong(3, textureData.timestamp());
            playerNameStmt.addBatch();
        }

        playerStmt.setString(1, player.id());
        playerStmt.setString(2, player.name());
        playerStmt.setObject(3, player.legacy());
        playerStmt.setObject(4, player.demo());
        playerStmt.setString(5, gson.toJson(player.profileActions()));
        playerStmt.setLong(6, lastUpdated);
        playerStmt.addBatch();
    }

    public static void ingest(final @NonNull String filePath) {
        final int BATCH_SIZE = 50000;
        final int QUEUE_CAPACITY = 50000;

        final Object2IntOpenHashMap<String> skinCache = new Object2IntOpenHashMap<>();
        final Object2IntOpenHashMap<String> capeCache = new Object2IntOpenHashMap<>();

        try (final var conn = ds.getConnection()) {
            System.out.println("Pre-populating skin cache...");
            try (final var stmt = conn.createStatement();
                 final var rs = stmt.executeQuery("SELECT id, hash FROM skins")) {
                while (rs.next()) skinCache.put(rs.getString("hash"), rs.getInt("id"));
            }
            System.out.println("Skin cache populated with " + skinCache.size() + " entries.");

            System.out.println("Pre-populating cape cache...");
            try (final var stmt = conn.createStatement();
                 final var rs = stmt.executeQuery("SELECT id, hash FROM capes")) {
                while (rs.next()) capeCache.put(rs.getString("hash"), rs.getInt("id"));
            }
            System.out.println("Cape cache populated with " + capeCache.size() + " entries.");
        } catch (final Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred while pre-populating caches.");
            return;
        }

        final LinkedBlockingQueue<String> lineQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        final LinkedBlockingQueue<ParsedPlayer> playerQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        final String POISON_PILL = "__DONE__";
        final ParsedPlayer PLAYER_POISON_PILL = new ParsedPlayer(new Player("", "", null, null, 0L, null, null), null);

        final long startTime = System.currentTimeMillis();

        @SuppressWarnings("resource")
        final ExecutorService executor = Executors.newFixedThreadPool(2);

        final AtomicBoolean failed = new AtomicBoolean(false);

        // Reader thread
        executor.submit(() -> {
            try (final BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineQueue.put(line);
                }
            } catch (final Exception e) {
                //noinspection CallToPrintStackTrace
                e.printStackTrace();
                failed.set(true);
            } finally {
                try { lineQueue.put(POISON_PILL); } catch (final InterruptedException ignored) {}
            }
        });

        // Deserializer thread
        executor.submit(() -> {
            try {
                while (true) {
                    final String line = lineQueue.take();
                    if (line.equals(POISON_PILL)) break;
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

                    playerQueue.put(new ParsedPlayer(player, textureData));
                }
            } catch (final Exception e) {
                //noinspection CallToPrintStackTrace
                e.printStackTrace();
                failed.set(true);
            } finally {
                try { playerQueue.put(PLAYER_POISON_PILL); } catch (final InterruptedException ignored) {}
            }
        });

        // DB thread (main)
        try (final var conn = ds.getConnection();
             final var playerStmt = conn.prepareStatement(INSERT_PLAYER_SQL);
             final var playerTextureStmt = conn.prepareStatement(INSERT_PLAYER_TEXTURE_SQL);
             final var playerNameStmt = conn.prepareStatement(INSERT_PLAYER_NAME_SQL)) {

            conn.setAutoCommit(false);

            int count = 0;
            while (true) {
                final ParsedPlayer parsedPlayer = playerQueue.take();
                if (parsedPlayer == PLAYER_POISON_PILL) break;
                if (failed.get()) {
                    System.out.println("Worker thread failed, aborting ingestion.");
                    break;
                }
                processPlayer(parsedPlayer, playerStmt, playerTextureStmt, playerNameStmt, conn, skinCache, capeCache);

                if (++count % BATCH_SIZE == 0) {
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
        } finally {
            executor.shutdown();
        }
    }

    record ParsedPlayer(Player player, @Nullable TextureData textureData) {}

    public record Player(
            @NonNull String id,
            @NonNull String name,
            @Nullable Boolean legacy,
            @Nullable Boolean demo,
            long timestamp,
            @Nullable JsonObject[] profileActions,
            Property @Nullable[] properties) {
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

    public record Property(@NonNull String name, @NonNull String value) {}

    public record TextureData(
            long timestamp,
            @NonNull String profileId,
            @NonNull String profileName,
            @NonNull Textures textures) {
        public static class Deserializer implements JsonDeserializer<TextureData> {
            private static final Set<String> KNOWN_FIELDS = Set.of("timestamp", "profileId", "profileName", "signatureRequired", "textures");
            private static final Gson delegate = new GsonBuilder().setLenient().create();

            @Override
            public TextureData deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) throws JsonParseException {
                final JsonObject obj = json.getAsJsonObject();
                for (final String key : obj.keySet()) {
                    if (!KNOWN_FIELDS.contains(key)) {
                        throw new JsonParseException("Unknown field '" + key + "' encountered in TextureData");
                    }
                }
                return delegate.fromJson(obj, TextureData.class);
            }
        }
    }

    // All Skins and Capes are prepended by: "http://textures.minecraft.net/texture/"
    public record Textures(
            @Nullable SkinTexture SKIN,
            @Nullable CapeTexture CAPE) {}

    public record SkinTexture(
            @NonNull String url,
            @Nullable SkinMetadata metadata) {
        public static class Deserializer implements JsonDeserializer<SkinTexture> {
            private static final Set<String> KNOWN_FIELDS = Set.of("url", "metadata");
            private static final Gson delegate = new GsonBuilder().setLenient().create();

            @Override
            public SkinTexture deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) throws JsonParseException {
                final JsonObject obj = json.getAsJsonObject();
                for (final String key : obj.keySet()) {
                    if (!KNOWN_FIELDS.contains(key)) {
                        throw new JsonParseException("Unknown field '" + key + "' encountered in SkinTexture");
                    }
                }
                return delegate.fromJson(obj, SkinTexture.class);
            }
        }
    }

    public record SkinMetadata(@Nullable String model) {}

    public record CapeTexture(@NonNull String url) {
        public static class Deserializer implements JsonDeserializer<CapeTexture> {
            private static final Set<String> KNOWN_FIELDS = Set.of("url");
            private static final Gson delegate = new GsonBuilder().setLenient().create();

            @Override
            public CapeTexture deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) throws JsonParseException {
                final JsonObject obj = json.getAsJsonObject();
                for (final String key : obj.keySet()) {
                    if (!KNOWN_FIELDS.contains(key)) {
                        throw new JsonParseException("Unknown field '" + key + "' encountered in CapeTexture");
                    }
                }
                return delegate.fromJson(obj, CapeTexture.class);
            }
        }
    }
}
