package dev.neuralnexus.archiveingest.data.players;

import org.jspecify.annotations.NonNull;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Base64;

import static dev.neuralnexus.archiveingest.data.players.Player.GSON;
import static dev.neuralnexus.archiveingest.data.players.Player.ds;
import static dev.neuralnexus.archiveingest.data.players.Player.processPlayer;

/**
 * Primarily for ingesting mojang.jsonl from: <br>
 * minecraft-uuids-2024-02-02 <br>
 * minecraft-uuids-2024-02-22 <br>
 * minecraft-uuids-2029-09-01
 */
public final class IngestJSONL {
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

    private static final String INSERT_PLAYER_SQL = """
        INSERT INTO players (id, name, legacy, demo, profile_actions, first_seen, last_seen) VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?, ?)
        ON CONFLICT (id) DO UPDATE SET
            name = CASE WHEN EXCLUDED.last_seen >= players.last_seen THEN EXCLUDED.name ELSE players.name END,
            legacy = COALESCE(EXCLUDED.legacy, players.legacy),
            demo = COALESCE(EXCLUDED.demo, players.demo),
            profile_actions = COALESCE(EXCLUDED.profile_actions, players.profile_actions),
            first_seen = LEAST(EXCLUDED.first_seen, players.first_seen),
            last_seen = GREATEST(EXCLUDED.last_seen, players.last_seen)
        """;

    public static void ingest(final @NonNull String filePath) {
        final int BATCH_SIZE = 10000;
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
                final Player player = GSON.fromJson(line, Player.class);

                TextureData textureData = null;
                if (player.properties() != null) {
                    for (final Property property : player.properties()) {
                        if (!property.name().equals("textures")) {
                            throw new IllegalStateException("Unknown property '" + property.name() + "' encountered for player: " + player.id());
                        }
                        final String decoded = new String(Base64.getDecoder().decode(property.value()));
                        textureData = GSON.fromJson(decoded, TextureData.class);
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
