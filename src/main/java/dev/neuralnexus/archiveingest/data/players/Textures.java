package dev.neuralnexus.archiveingest.data.players;

import org.jspecify.annotations.Nullable;

// All Skins and Capes are prepended by: "http://textures.minecraft.net/texture/"
public record Textures(
        @Nullable SkinTexture SKIN,
        @Nullable CapeTexture CAPE) {
}
