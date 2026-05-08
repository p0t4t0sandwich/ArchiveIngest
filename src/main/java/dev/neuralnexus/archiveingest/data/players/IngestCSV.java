package dev.neuralnexus.archiveingest.data.players;

import org.jspecify.annotations.NonNull;

import java.io.BufferedReader;
import java.io.FileReader;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static dev.neuralnexus.archiveingest.data.players.Player.ds;

/**
 * Primarily for ingesting players.csv from: <br>
 * minecraft-uuids-2024-02-02 <br>
 * minecraft-uuids-2024-02-22 <br>
 * minecraft-uuids-2029-09-01
 */
public final class IngestCSV {
    private static final String INSERT_PLAYER_SQL = """
        INSERT INTO players (id, name, legacy, demo, profile_actions, first_seen, last_seen) VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?, ?)
        ON CONFLICT (id) DO UPDATE SET
            first_seen = LEAST(EXCLUDED.first_seen, players.first_seen),
            last_seen = GREATEST(EXCLUDED.last_seen, players.last_seen)
        """;

    private static final String INSERT_PLAYER_NAME_SQL = """
        INSERT INTO player_names (player_id, name, first_seen, last_seen) VALUES (?::uuid, ?, ?, ?)
        ON CONFLICT (player_id, name) DO NOTHING
        """;

    public static void ingest(final @NonNull String filePath, final @NonNull String date) {
        final long timestamp = LocalDateTime.parse(date,
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .toEpochSecond(ZoneOffset.UTC) * 1000L;

        final int BATCH_SIZE = 50000;
        final long startTime = System.currentTimeMillis();

        try (final BufferedReader reader = new BufferedReader(new FileReader(filePath));
             final var conn = ds.getConnection();
             final var playerStmt = conn.prepareStatement(INSERT_PLAYER_SQL);
             final var playerNameStmt = conn.prepareStatement(INSERT_PLAYER_NAME_SQL)) {

            conn.setAutoCommit(false);

            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                final String[] parts = line.split(",", 2);
                if (parts.length < 2) continue;

                final String id = parts[0].trim();
                final String name = parts[1].trim();

                playerStmt.setString(1, id);
                playerStmt.setString(2, name);
                playerStmt.setNull(3, Types.BOOLEAN);
                playerStmt.setNull(4, Types.BOOLEAN);
                playerStmt.setNull(5, Types.OTHER);
                playerStmt.setLong(6, timestamp);
                playerStmt.setLong(7, timestamp);
                playerStmt.addBatch();

                playerNameStmt.setString(1, id);
                playerNameStmt.setString(2, name);
                playerNameStmt.setLong(3, timestamp);
                playerNameStmt.setLong(4, timestamp);
                playerNameStmt.addBatch();

                if (++count % BATCH_SIZE == 0) {
                    playerStmt.executeBatch();
                    playerNameStmt.executeBatch();
                    conn.commit();
                    final long elapsed = System.currentTimeMillis() - startTime;
                    System.out.printf("Rows processed: %d | Elapsed: %ds | Rate: %d rows/s%n",
                            count, elapsed / 1000, count / Math.max(1, elapsed / 1000));
                }
            }

            // Flush remaining
            playerStmt.executeBatch();
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
