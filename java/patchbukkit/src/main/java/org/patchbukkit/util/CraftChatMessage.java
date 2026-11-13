package org.patchbukkit.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;

/**
 * The subset of CraftBukkit's CraftChatMessage that plugins reach through reflection (for example
 * Simple Voice Chat looks up util.CraftChatMessage#fromJSON). Styling is dropped: the JSON text
 * fields are joined into one plain component.
 */
public final class CraftChatMessage {

    private CraftChatMessage() {
    }

    public static Component fromJSON(String json) {
        StringBuilder text = new StringBuilder();
        try {
            appendText(JsonParser.parseString(json), text);
        } catch (RuntimeException ignored) {
            text.setLength(0);
            text.append(json);
        }
        return Component.literal(text.toString());
    }

    private static void appendText(JsonElement element, StringBuilder out) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            out.append(element.getAsString());
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                appendText(child, out);
            }
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("text")) {
                appendText(object.get("text"), out);
            }
            if (object.has("extra")) {
                appendText(object.get("extra"), out);
            }
        }
    }
}
