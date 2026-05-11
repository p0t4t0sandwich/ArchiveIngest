package dev.neuralnexus.archiveingest;

import dev.neuralnexus.archiveingest.compression.CompressionType;
import dev.neuralnexus.archiveingest.compression.GZip;
import dev.neuralnexus.archiveingest.compression.SevenZip;
import dev.neuralnexus.archiveingest.compression.Zstd;
import dev.neuralnexus.archiveingest.data.mca.mods.Mod;
import dev.neuralnexus.archiveingest.data.players.IngestCSV;
import dev.neuralnexus.archiveingest.data.players.IngestJSONL;
import dev.neuralnexus.archiveingest.data.players.textures.IngestPNG7z;
import org.jspecify.annotations.NonNull;

import java.nio.file.Path;

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
                if (args.length > 3) {
                    String[] options = new String[args.length - 3];
                    System.arraycopy(args, 3, options, 0, options.length);
                    handleDataCommand(args[1], args[2], options);
                } else {
                    handleDataCommand(args[1], args[2]);
                }
            }
            default -> {
                System.out.println("Unsupported command: " + command);
                System.out.println("Supported commands: " + DECOMPRESS_COMMAND + ", " + DATA_COMMAND);
            }
        }
    }

    private static void handleDecompression(final @NonNull String type, final @NonNull String inputFile, final @NonNull String outputFile) {
        switch (CompressionType.of(type)) {
            case GZIP -> GZip.decompress(inputFile, outputFile);
            case SEVEN_ZIP -> SevenZip.decompress(inputFile, outputFile);
            case ZSTD -> Zstd.decompress(inputFile, outputFile);
            default -> System.out.println("Unsupported compression type: " + type);
        }
    }

    private static void handleDataCommand(final @NonNull String subcommand, final @NonNull String inputFile, final String... options) {
        switch (subcommand) {
            case "players" -> {
                switch (inputFile.split("\\.")[1]) {
                    case "jsonl" -> IngestJSONL.ingest(inputFile);
                    case "csv" -> IngestCSV.ingest(inputFile, options[0]);
                    default -> System.out.println("Unsupported file format for players: " + inputFile);
                }
            }
            case "textures" -> IngestPNG7z.ingestDirOf7z(inputFile, options[0]);
            case "mod" -> {
                final Path input = Path.of(inputFile);
                if (!input.toFile().isFile()) {
                    System.out.println("Input file must be a file for mod ingestion: " + inputFile);
                    return;
                }
                final Path doneDir = Path.of(options[0]);
                if (!doneDir.toFile().exists()) {
                    if (!doneDir.toFile().mkdirs()) {
                        System.out.println("Failed to create done directory: " + doneDir);
                        return;
                    }
                }
                final Path failedDir = Path.of(options[1]);
                if (!failedDir.toFile().exists()) {
                    if (!failedDir.toFile().mkdirs()) {
                        System.out.println("Failed to create failed directory: " + failedDir);
                        return;
                    }
                }
                Mod.ingest(input, doneDir, failedDir);
            }
            case "mods" -> {
                final Path inputDir = Path.of(inputFile);
                if (!inputDir.toFile().isDirectory()) {
                    System.out.println("Input file must be a directory for mods ingestion: " + inputFile);
                    return;
                }
                final Path doneDir = Path.of(options[0]);
                if (!doneDir.toFile().exists()) {
                    if (!doneDir.toFile().mkdirs()) {
                        System.out.println("Failed to create done directory: " + doneDir);
                        return;
                    }
                }
                final Path failedDir = Path.of(options[1]);
                if (!failedDir.toFile().exists()) {
                    if (!failedDir.toFile().mkdirs()) {
                        System.out.println("Failed to create failed directory: " + failedDir);
                        return;
                    }
                }
                Mod.ingestMods(inputDir, doneDir, failedDir);
            }
            default -> System.out.println("Unsupported data subcommand: " + subcommand);
        }
    }
}
