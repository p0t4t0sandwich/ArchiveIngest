package dev.neuralnexus.archiveingest.compression;

import com.github.luben.zstd.ZstdInputStream;
import org.jspecify.annotations.NonNull;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Zstd {
    public static void decompress(final @NonNull String inputFile, final @NonNull String outputFile) {
        try (final ZstdInputStream zis = new ZstdInputStream(Files.newInputStream(Paths.get(inputFile)));
                final OutputStream os = Files.newOutputStream(Paths.get(outputFile))) {
            final byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = zis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            System.out.println("Decompression completed successfully.");
        } catch (final Exception e) {
            e.printStackTrace();
            System.out.println("An error occurred during decompression.");
        }
    }
}
