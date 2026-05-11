package dev.neuralnexus.archiveingest.data;

import dev.neuralnexus.archiveingest.data.mca.Hashes;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class HashUtil {
    private HashUtil() {}

    public static Hashes hash(@NonNull Path path) {
        try {
            final MessageDigest md5    = MessageDigest.getInstance("MD5");
            final MessageDigest sha1   = MessageDigest.getInstance("SHA-1");
            final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            final MessageDigest sha512 = MessageDigest.getInstance("SHA-512");

            long size = 0;
            byte[] buffer = new byte[8192];
            int read;

            try (InputStream in = Files.newInputStream(path)) {
                while ((read = in.read(buffer)) != -1) {
                    md5.update(buffer, 0, read);
                    sha1.update(buffer, 0, read);
                    sha256.update(buffer, 0, read);
                    sha512.update(buffer, 0, read);
                    size += read;
                }
            }

            HexFormat hex = HexFormat.of();
            return new Hashes(
                    size,
                    hex.formatHex(md5.digest()),
                    hex.formatHex(sha1.digest()),
                    hex.formatHex(sha256.digest()),
                    hex.formatHex(sha512.digest())
            );

        } catch (final NoSuchAlgorithmException e) {
            throw new RuntimeException("Required digest algorithm not available", e);
        } catch (final IOException e) {
            throw new RuntimeException("Failed to read file for hashing: " + path, e);
        }
    }
}
