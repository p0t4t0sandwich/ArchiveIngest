package dev.neuralnexus.archiveingest.data.players;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Set;

public record Player(
        @NonNull String id,
        @NonNull String name,
        @Nullable Boolean legacy,
        @Nullable Boolean demo,
        long timestamp,
        @Nullable JsonObject[] profileActions,
        Property @Nullable [] properties) {
    public static class Deserializer implements JsonDeserializer<Player> {
        private static final Set<String> KNOWN_FIELDS = Set.of("id", "name", "legacy", "demo", "properties", "profileActions");
        private static final Gson delegate = new GsonBuilder().setLenient().create();

        @Override
        public Player deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) throws JsonParseException {
            final JsonObject obj = json.getAsJsonObject();
            for (final String key : obj.keySet()) {
                if (!KNOWN_FIELDS.contains(key)) {
                    throw new JsonParseException("Unknown field '" + key + "' encountered");
                }
            }
            return delegate.fromJson(obj, Player.class);
        }
    }
}
