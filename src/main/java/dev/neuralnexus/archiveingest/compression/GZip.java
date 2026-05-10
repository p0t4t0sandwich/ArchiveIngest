package dev.neuralnexus.archiveingest.compression;

import org.jspecify.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;
import java.io.OutputStream;

public class GZip {
    public static void decompress(final @NonNull String inputFile, final @NonNull String outputFile) {
        System.out.println("Starting GZip decompression for file: " + inputFile);
        try (final GZIPInputStream gis = new GZIPInputStream(Files.newInputStream(Paths.get(inputFile)));
             final OutputStream os = Files.newOutputStream(Paths.get(outputFile))) {
            final byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = gis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            System.out.println("Decompression completed successfully.");
        } catch (final IOException e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred during decompression.");
        }
    }
}
