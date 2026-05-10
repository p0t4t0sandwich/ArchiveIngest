package dev.neuralnexus.archiveingest.data;

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

    public record FileHashes(
            long size,
            @NonNull String md5,
            @NonNull String sha1,
            @NonNull String sha256,
            @NonNull String sha512
    ) {}

    public static FileHashes hash(@NonNull Path path) {
        try {
            MessageDigest md5    = MessageDigest.getInstance("MD5");
            MessageDigest sha1   = MessageDigest.getInstance("SHA-1");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");

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
            return new FileHashes(
                    size,
                    hex.formatHex(md5.digest()),
                    hex.formatHex(sha1.digest()),
                    hex.formatHex(sha256.digest()),
                    hex.formatHex(sha512.digest())
            );

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Required digest algorithm not available", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file for hashing: " + path, e);
        }
    }
}
