package dev.neuralnexus.archiveingest.compression;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class SevenZip {
    public static void decompress(final @NonNull String inputFile, final @NonNull String outputFile) {
        try (final SevenZFile sevenZFile = new SevenZFile(new File(inputFile))) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZFile.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    try (final FileOutputStream fos = new FileOutputStream(outputFile)) {
                        final byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = sevenZFile.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                }
            }
            System.out.println("Decompression completed successfully.");
        } catch (final IOException e) {
            e.printStackTrace();
            System.out.println("An error occurred during decompression.");
        }
    }
}
