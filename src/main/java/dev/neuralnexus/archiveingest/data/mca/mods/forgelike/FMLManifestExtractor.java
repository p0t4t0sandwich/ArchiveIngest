package dev.neuralnexus.archiveingest.data.mca.mods.forgelike;

import dev.neuralnexus.archiveingest.data.mca.Link;
import dev.neuralnexus.archiveingest.data.mca.mods.ExtractResult;
import dev.neuralnexus.archiveingest.data.mca.mods.ModLoader;
import dev.neuralnexus.archiveingest.data.mca.mods.ModLoaderMeta;
import dev.neuralnexus.archiveingest.data.mca.mods.ModVersion;
import dev.neuralnexus.archiveingest.data.mca.mods.Side;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.StreamSupport;

public final class FMLManifestExtractor {
    private static final String MANIFEST_FILE = "META-INF/MANIFEST.MF";
    private static final String SERVICE_PREFIX = "META-INF/services/";
    private static final List<String> SERVICE_PREFIXES = List.of(
            "net.neoforged.",
            "net.minecraftforge.",
            "cpw.mods."
    );

    private FMLManifestExtractor() {}

    public static boolean supports(final @NonNull JarFile jar) throws IOException {
        // Check for FMLModType in manifest
        final Manifest manifest = jar.getManifest();
        if (manifest != null && manifest.getMainAttributes().getValue("FMLModType") != null) {
            return true;
        }

        // Check for known Forge/Neo service files
        return jar.entries().asIterator().hasNext() &&
                StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                                jar.entries().asIterator(), Spliterator.ORDERED), false)
                        .filter(e -> e.getName().startsWith(SERVICE_PREFIX))
                        .map(e -> e.getName().substring(SERVICE_PREFIX.length()))
                        .anyMatch(name -> SERVICE_PREFIXES.stream().anyMatch(name::startsWith));
    }

    public static @NonNull ExtractResult extract(final long archiveItemId, final @NonNull Path jarPath) throws IOException {
        try (final JarFile jar = new JarFile(jarPath.toFile())) {
            final Manifest manifest = jar.getManifest();
            final Attributes attrs = manifest != null
                    ? manifest.getMainAttributes()
                    : new Attributes();

            final String fmlModType  = attrs.getValue("FMLModType");
            final String implTitle   = attrs.getValue("Implementation-Title") != null
                    ? attrs.getValue("Implementation-Title")
                    : attrs.getValue("Specification-Title");
            final String implVersion = attrs.getValue("Implementation-Version") != null
                    ? attrs.getValue("Implementation-Version")
                    : attrs.getValue("Specification-Version");
            final String implVendor  = attrs.getValue("Implementation-Vendor") != null
                    ? attrs.getValue("Implementation-Vendor")
                    : attrs.getValue("Specification-Vendor");
            final String moduleName  = attrs.getValue("Automatic-Module-Name");
            final String implURL     = attrs.getValue("Implementation-URL");
            final String builtOnMC   = attrs.getValue("Built-On-Minecraft") != null
                    ? attrs.getValue("Built-On-Minecraft")
                    : attrs.getValue("Built-on-Minecraft");

            // --- modId ---
            final String modId = resolveModId(moduleName, implTitle, jarPath);

            // --- version ---
            final String version = implVersion != null ? implVersion : "unknown";

            // --- names ---
            final List<String> names = implTitle != null ? List.of(implTitle) : List.of();

            // --- authors ---
            final List<String> authors = implVendor != null ? List.of(implVendor) : List.of();

            // --- links ---
            final List<Link> links = new ArrayList<>();
            if (implURL != null) links.add(new Link("homepage", implURL));

            // --- loader meta ---
            final List<ModLoaderMeta> loaderMetas = new ArrayList<>();
            final var detectedLoaders = StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                            jar.entries().asIterator(), Spliterator.ORDERED), false)
                    .filter(e -> e.getName().startsWith(SERVICE_PREFIX))
                    .map(e -> e.getName().substring(SERVICE_PREFIX.length()))
                    .flatMap(name -> {
                        final List<ModLoader> matched = new ArrayList<>();
                        if (name.startsWith("net.neoforged."))      matched.add(ModLoader.NEOFORGE);
                        if (name.startsWith("net.minecraftforge.")) matched.add(ModLoader.FORGE);
                        if (name.startsWith("cpw.mods."))           matched.add(ModLoader.FORGE);
                        return matched.stream();
                    })
                    .distinct()
                    .toList();

            for (final ModLoader loader : detectedLoaders.isEmpty() ? List.of(ModLoader.FORGE) : detectedLoaders) {
                loaderMetas.add(new ModLoaderMeta(
                        loader,
                        null, // apiVersion — not declared in manifest
                        null,          // loaderVersion — not declared in manifest
                        builtOnMC != null ? builtOnMC : "unknown",
                        MANIFEST_FILE
                ));
            }

            return new ExtractResult(
                    new ModVersion(
                            archiveItemId,
                            modId,
                            names,
                            version,
                            null,           // description
                            null,           // license
                            authors,
                            List.of(),      // contributors
                            List.of(),      // credits
                            loaderMetas,
                            List.of(),      // dependencies — not declared in manifest
                            parseSide(fmlModType)
                    ),
                    links
            );
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static @NonNull String resolveModId(
            final @Nullable String moduleName,
            final @Nullable String implTitle,
            final @NonNull Path jarPath) {
        if (moduleName != null && !moduleName.isBlank()) return moduleName.trim().toLowerCase();
        if (implTitle != null && !implTitle.isBlank()) return implTitle.trim().toLowerCase().replace(' ', '_');
        return jarPath.getFileName().toString().replaceAll("\\.jar$", "").toLowerCase();
    }

    private static Side parseSide(final @Nullable String fmlModType) {
        if (fmlModType == null) return Side.BOTH;
        return switch (fmlModType.toUpperCase()) {
            case "LANGPROVIDER", "LIBRARY", "GAMELIBRARY" -> Side.BOTH;
            default -> Side.BOTH;
        };
    }
}
