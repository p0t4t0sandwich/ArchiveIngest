package dev.neuralnexus.archiveingest.data.mca;

import com.google.gson.annotations.SerializedName;

public enum Side {
    @SerializedName("client")  CLIENT,
    @SerializedName("server")  SERVER,
    @SerializedName("both")    BOTH,
    @SerializedName("proxy")   PROXY,
    @SerializedName("unknown") UNKNOWN
}
