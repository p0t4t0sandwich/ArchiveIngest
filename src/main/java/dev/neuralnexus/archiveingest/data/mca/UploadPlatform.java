package dev.neuralnexus.archiveingest.data.mca;

import com.google.gson.annotations.SerializedName;

public enum UploadPlatform {
    @SerializedName("CurseForge") CURSEFORGE,
    @SerializedName("Modrinth")   MODRINTH
}
