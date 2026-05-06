package dev.neuralnexus.archiveingest.data.players;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Type;
import java.util.Set;

public record TextureData(
        long timestamp,
        @NonNull String profileId,
        @NonNull String profileName,
        @NonNull Textures textures) {
    public static class Deserializer implements JsonDeserializer<TextureData> {
        private static final Set<String> KNOWN_FIELDS = Set.of("timestamp", "profileId", "profileName", "signatureRequired", "textures");
        private static final Gson delegate = new GsonBuilder().setLenient().create();

        @Override
        public TextureData deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) throws JsonParseException {
            final JsonObject obj = json.getAsJsonObject();
            for (final String key : obj.keySet()) {
                if (!KNOWN_FIELDS.contains(key)) {
                    throw new JsonParseException("Unknown field '" + key + "' encountered in TextureData");
                }
            }
            return delegate.fromJson(obj, TextureData.class);
        }
    }
}
