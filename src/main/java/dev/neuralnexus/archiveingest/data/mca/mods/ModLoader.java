package dev.neuralnexus.archiveingest.data.mca.mods;

import com.google.gson.annotations.SerializedName;

public enum ModLoader {
    @SerializedName("Fabric")     FABRIC,
    @SerializedName("Quilt")      QUILT,
    @SerializedName("Forge")      FORGE,
    @SerializedName("NeoForge")   NEOFORGE,
    @SerializedName("Bukkit")     BUKKIT,
    @SerializedName("Spigot")     SPIGOT,
    @SerializedName("Paper")      PAPER,
    @SerializedName("BungeeCord") BUNGEECORD,
    @SerializedName("Velocity")   VELOCITY,
    @SerializedName("Sponge")     SPONGE,
    @SerializedName("Ignite")     IGNITE
}
