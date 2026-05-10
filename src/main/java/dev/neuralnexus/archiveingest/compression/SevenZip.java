package dev.neuralnexus.archiveingest.compression;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

public class SevenZip {
    public static void decompress(final @NonNull String inputFile, final @NonNull String outputFile) {
        System.out.println("Starting 7-Zip decompression for file: " + inputFile);
        //noinspection deprecation
        try (final SevenZFile sevenZFile = new SevenZFile(new File(inputFile))) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZFile.getNextEntry()) != null) {
                final File file = new File(outputFile, entry.getName());
                if (entry.isDirectory()) {
                    if (!file.exists() && !file.mkdirs()) {
                        System.out.println("Failed to create directory: " + file.getAbsolutePath());
                    }
                } else {
                    try (final OutputStream os = Files.newOutputStream(Paths.get(file.getAbsolutePath()))) {
                        final byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = sevenZFile.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    } catch (final IOException e) {
                        //noinspection CallToPrintStackTrace
                        e.printStackTrace();
                        System.out.println("An error occurred while extracting file: " + file.getAbsolutePath());
                    }
                }
            }
            System.out.println("Decompression completed successfully.");
        } catch (final IOException e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            System.out.println("An error occurred during decompression.");
        }
    }
}
