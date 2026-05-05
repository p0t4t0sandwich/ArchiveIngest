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
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
        final String createSkinsSQL = """
        CREATE TABLE IF NOT EXISTS skins (
            id SERIAL PRIMARY KEY,
            hash TEXT NOT NULL,
            model TEXT,
            UNIQUE (hash, model)
        );
    """;
        final String createCapesSQL = """
        CREATE TABLE IF NOT EXISTS capes (
            id SERIAL PRIMARY KEY,
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

    private static final String INSERT_PLAYER_TEXTURE_SQL =
            "INSERT INTO player_textures (player_id, skin_id, cape_id, timestamp) VALUES (?::uuid, ?, ?, ?) ON CONFLICT (player_id, timestamp) DO NOTHING";
    private static final String INSERT_PLAYER_NAME_SQL =
            "INSERT INTO player_names (player_id, name, timestamp) VALUES (?::uuid, ?, ?) ON CONFLICT (player_id, name, timestamp) DO NOTHING";


    private static void processPlayer(
            final @NonNull ResolvedPlayer resolvedPlayer,
            final @NonNull PreparedStatement playerStmt,
            final @NonNull PreparedStatement playerTextureStmt,
            final @NonNull PreparedStatement playerNameStmt) throws SQLException {

        final Player player = resolvedPlayer.player();
        final TextureData textureData = resolvedPlayer.textureData();

        long lastUpdated = player.timestamp();

        if (textureData != null) {
            lastUpdated = Math.max(lastUpdated, textureData.timestamp());

            playerTextureStmt.setString(1, player.id());
            playerTextureStmt.setObject(2, resolvedPlayer.skinId());
            playerTextureStmt.setObject(3, resolvedPlayer.capeId());
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

    private static void preIngest(
            final @NonNull String filePath,
            final @NonNull ConcurrentHashMap<String, Integer> skinCache,
            final @NonNull ConcurrentHashMap<String, Integer> capeCache) {

        final long startTime = System.currentTimeMillis();

        final HashMap<String, String> allSkinHashes = new HashMap<>(); // hash -> model
        final HashSet<String> allCapeHashes = new HashSet<>();

        // Hash collection
        System.out.println("Collecting hashes...");
        try (final BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                final Player player = gson.fromJson(line, Player.class);
                if (player.properties() != null) {
                    for (final Property property : player.properties()) {
                        if (!property.name().equals("textures")) {
                            throw new IllegalStateException("Unknown property '" + property.name() + "' encountered for player: " + player.id());
                        }
                        final String decoded = new String(Base64.getDecoder().decode(property.value()));
                        final TextureData textureData = gson.fromJson(decoded, TextureData.class);
                        if (textureData.textures().SKIN() != null) {
                            final String skinHash = textureData.textures().SKIN().url().replace(TEXTURE_BASE_URL, "");
                            final String model = textureData.textures().SKIN().metadata() != null
                                    ? textureData.textures().SKIN().metadata().model()
                                    : null;
                            allSkinHashes.putIfAbsent(skinHash, model);
                        }
                        if (textureData.textures().CAPE() != null) {
                            allCapeHashes.add(textureData.textures().CAPE().url().replace(TEXTURE_BASE_URL, ""));
                        }
                    }
                }
                if (++count % 1000000 == 0) {
                    System.out.printf("Hash collection: %d rows | Unique skins: %d | Unique capes: %d%n",
                            count, allSkinHashes.size(), allCapeHashes.size());
                }
            }
        } catch (final Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred during hash collection.");
            return;
        }

        final long collectionElapsed = System.currentTimeMillis() - startTime;
        System.out.printf("Hash collection completed. Unique skins: %d | Unique capes: %d | Elapsed: %ds%n",
                allSkinHashes.size(), allCapeHashes.size(), collectionElapsed / 1000);

        // Bulk upsert skins via staging table
        System.out.println("Upserting skins...");
        try (final var conn = ds.getConnection();
             final var s = conn.createStatement()) {
            s.execute("SET work_mem = '1GB'");
            s.execute("CREATE TEMP TABLE skins_staging (hash TEXT, model TEXT)");

            final var copyManager = new CopyManager(conn.unwrap(BaseConnection.class));
            final StringBuilder data = new StringBuilder();
            for (final Map.Entry<String, String> entry : allSkinHashes.entrySet()) {
                data.append(entry.getKey())
                        .append('\t')
                        .append(entry.getValue() == null ? "\\N" : entry.getValue())
                        .append('\n');
            }
            copyManager.copyIn("COPY skins_staging FROM STDIN", new StringReader(data.toString()));
            System.out.println("Skin staging table populated.");

            s.execute("""
            INSERT INTO skins (hash, model)
            SELECT hash, model FROM skins_staging
            WHERE NOT EXISTS (
                SELECT 1 FROM skins
                WHERE skins.hash = skins_staging.hash
                AND skins.model IS NOT DISTINCT FROM skins_staging.model
            )
            """);

            try (final var rs = s.executeQuery("SELECT id, hash, model FROM skins")) {
                while (rs.next()) skinCache.put(rs.getString("hash") + ":" + rs.getString("model"), rs.getInt("id"));
            }
            System.out.println("Skins upserted: " + skinCache.size());
        } catch (final Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred while upserting skins.");
            return;
        }

        // Bulk upsert capes via staging table
        System.out.println("Upserting capes...");
        try (final var conn = ds.getConnection();
             final var s = conn.createStatement()) {
            s.execute("SET work_mem = '1GB'");
            s.execute("CREATE TEMP TABLE capes_staging (hash TEXT)");

            final var copyManager = new CopyManager(conn.unwrap(BaseConnection.class));
            final StringBuilder data = new StringBuilder();
            for (final String hash : allCapeHashes) {
                data.append(hash).append('\n');
            }
            copyManager.copyIn("COPY capes_staging FROM STDIN", new StringReader(data.toString()));
            System.out.println("Cape staging table populated.");

            s.execute("""
            INSERT INTO capes (hash)
            SELECT hash FROM capes_staging
            WHERE NOT EXISTS (
                SELECT 1 FROM capes
                WHERE capes.hash = capes_staging.hash
            )
            """);

            try (final var rs = s.executeQuery("SELECT id, hash FROM capes")) {
                while (rs.next()) capeCache.put(rs.getString("hash"), rs.getInt("id"));
            }
            System.out.println("Capes upserted: " + capeCache.size());
        } catch (final Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred while upserting capes.");
            return;
        }

        final long totalElapsed = System.currentTimeMillis() - startTime;
        System.out.printf("Pre-ingestion completed. Elapsed: %ds%n", totalElapsed / 1000);
    }

    public static void ingest(final @NonNull String filePath) {
        final ConcurrentHashMap<String, Integer> skinCache = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Integer> capeCache = new ConcurrentHashMap<>();

        System.out.println("Starting pre-ingestion...");
        preIngest(filePath, skinCache, capeCache);

        // Skip DB cache population — caches already warm from preIngest
        System.out.println("Skin cache populated with " + skinCache.size() + " entries.");
        System.out.println("Cape cache populated with " + capeCache.size() + " entries.");

        final int BATCH_SIZE = 50000;
        final int QUEUE_CAPACITY = 10000;

        final LinkedBlockingQueue<String> lineQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        final LinkedBlockingQueue<ParsedPlayer> playerQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        final LinkedBlockingQueue<ResolvedPlayer> resolvedQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

        final String POISON_PILL = "__DONE__";
        final ParsedPlayer PARSED_POISON_PILL = new ParsedPlayer(new Player("", "", null, null, 0L, null, null), null);
        final ResolvedPlayer RESOLVED_POISON_PILL = new ResolvedPlayer(new Player("", "", null, null, 0L, null, null), null, null, null);

        final int DESERIALIZER_THREADS = 1;
        final int RESOLUTION_THREADS = 3;

        @SuppressWarnings("resource")
        final ExecutorService executor = Executors.newFixedThreadPool(1 + DESERIALIZER_THREADS + RESOLUTION_THREADS);

        final AtomicBoolean failed = new AtomicBoolean(false);
        final AtomicInteger deserializerThreadsDone = new AtomicInteger(0);
        final AtomicInteger resolutionThreadsDone = new AtomicInteger(0);

        final long startTime = System.currentTimeMillis();

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
                try {
                    lineQueue.put(POISON_PILL);
                } catch (final InterruptedException ignored) {}
            }
        });

        // Deserializer threads
        for (int i = 0; i < DESERIALIZER_THREADS; i++) {
            executor.submit(() -> {
                try {
                    while (true) {
                        final String line = lineQueue.take();
                        if (line.equals(POISON_PILL)) {
                            lineQueue.put(POISON_PILL);
                            break;
                        }
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
                    if (deserializerThreadsDone.incrementAndGet() == DESERIALIZER_THREADS) {
                        try {
                            playerQueue.put(PARSED_POISON_PILL);
                        } catch (final InterruptedException ignored) {}
                    }
                }
            });
        }

        // Resolution threads
        for (int i = 0; i < RESOLUTION_THREADS; i++) {
            executor.submit(() -> {
                final Object2IntOpenHashMap<String> localSkinCache = new Object2IntOpenHashMap<>(skinCache);
                final Object2IntOpenHashMap<String> localCapeCache = new Object2IntOpenHashMap<>(capeCache);
                try {
                    while (true) {
                        final ParsedPlayer parsedPlayer = playerQueue.take();
                        if (parsedPlayer == PARSED_POISON_PILL) {
                            // Re-queue poison pill for other resolution threads
                            playerQueue.put(PARSED_POISON_PILL);
                            break;
                        }
                        if (failed.get()) break;

                        final TextureData textureData = parsedPlayer.textureData();
                        Integer skinId = null;
                        Integer capeId = null;

                        if (textureData != null) {
                            if (textureData.textures().SKIN() != null) {
                                final String skinHash = textureData.textures().SKIN().url().replace(TEXTURE_BASE_URL, "");
                                final String model = textureData.textures().SKIN().metadata() != null
                                        ? textureData.textures().SKIN().metadata().model()
                                        : null;
                                //noinspection deprecation
                                skinId = localSkinCache.get(skinHash + ":" + model);
                            }

                            if (textureData.textures().CAPE() != null) {
                                final String capeHash = textureData.textures().CAPE().url().replace(TEXTURE_BASE_URL, "");
                                //noinspection deprecation
                                capeId = localCapeCache.get(capeHash);
                            }
                        }

                        resolvedQueue.put(new ResolvedPlayer(parsedPlayer.player(), textureData, skinId, capeId));
                    }
                } catch (final Exception e) {
                    //noinspection CallToPrintStackTrace
                    e.printStackTrace();
                    failed.set(true);
                } finally {
                    if (resolutionThreadsDone.incrementAndGet() == RESOLUTION_THREADS) {
                        try {
                            resolvedQueue.put(RESOLVED_POISON_PILL);
                        } catch (final InterruptedException ignored) {}
                    }
                }
            });
        }

        // DB insert thread (main)
        try (final var conn = ds.getConnection();
             final var playerStmt = conn.prepareStatement(INSERT_PLAYER_SQL);
             final var playerTextureStmt = conn.prepareStatement(INSERT_PLAYER_TEXTURE_SQL);
             final var playerNameStmt = conn.prepareStatement(INSERT_PLAYER_NAME_SQL)) {

            conn.setAutoCommit(false);

            int count = 0;
            while (true) {
                final ResolvedPlayer resolvedPlayer = resolvedQueue.take();
                if (resolvedPlayer == RESOLVED_POISON_PILL) break;
                if (failed.get()) {
                    System.out.println("Worker thread failed, aborting ingestion.");
                    break;
                }
                processPlayer(resolvedPlayer, playerStmt, playerTextureStmt, playerNameStmt);

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

            if (!failed.get()) {
                playerStmt.executeBatch();
                playerTextureStmt.executeBatch();
                playerNameStmt.executeBatch();
                conn.commit();
                final long elapsed = System.currentTimeMillis() - startTime;
                System.out.printf("Ingestion completed successfully. Rows processed: %d | Elapsed: %ds | Rate: %d rows/s%n",
                        count, elapsed / 1000, count / Math.max(1, elapsed / 1000));
            }
        } catch (final Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred during ingestion.");
        } finally {
            executor.shutdown();
        }
    }

    public record ParsedPlayer(Player player, @Nullable TextureData textureData) {}

    public record ResolvedPlayer(
            @NonNull Player player,
            @Nullable TextureData textureData,
            @Nullable Integer skinId,
            @Nullable Integer capeId) {}

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
