package dev.neuralnexus.archiveingest;

import dev.neuralnexus.archiveingest.compression.CompressionType;
import dev.neuralnexus.archiveingest.compression.Zstd;
import dev.neuralnexus.archiveingest.data.players.IngestJSONL;
import org.jspecify.annotations.NonNull;

public class Main {
    private static final String DECOMPRESS_COMMAND = "decompress";
    private static final String DATA_COMMAND = "data";

    static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: <command> [options]");
            System.out.println("Commands:");
            System.out.println("  decompress <compression_type> <input_file> <output_file>");
            System.out.println("  data <subcommand> <input_file>");
            return;
        }

        final String command = args[0];
        switch (command) {
            case DECOMPRESS_COMMAND -> {
                if (args.length < 4) {
                    System.out.println("Usage: decompress <compression_type> <input_file> <output_file>");
                    return;
                }
                final String compressionType = args[1];
                final String inputFile = args[2];
                final String outputFile = args[3];
                handleDecompression(compressionType, inputFile, outputFile);
            }
            case DATA_COMMAND -> {
                if (args.length < 3) {
                    System.out.println("Usage: data <subcommand> <input_file>");
                    return;
                }
                handleDataCommand(args[1], args[2]);
            }
            default -> {
                System.out.println("Unsupported command: " + command);
                System.out.println("Supported commands: " + DECOMPRESS_COMMAND + ", " + DATA_COMMAND);
            }
        }
    }

    private static void handleDecompression(final @NonNull String type, final @NonNull String inputFile, final @NonNull String outputFile) {
        //noinspection SwitchStatementWithTooFewBranches
        switch (CompressionType.of(type)) {
            case ZSTD:
                Zstd.decompress(inputFile, outputFile);
                break;
            default:
                System.out.println("Unsupported compression type: " + type);
                break;
        }
    }

    private static void handleDataCommand(final @NonNull String subcommand, final @NonNull String inputFile) {
        //noinspection SwitchStatementWithTooFewBranches
        switch (subcommand) {
            case "players" -> IngestJSONL.ingest(inputFile);
            default -> System.out.println("Unsupported data subcommand: " + subcommand);
        }
    }
}
