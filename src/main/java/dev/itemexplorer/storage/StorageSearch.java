package dev.itemexplorer.storage;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Pure query matching. Resolve translatable names in the calling client's language. */
public final class StorageSearch {
    public static final int MAX_QUERY = 64;
    public static final int MAX_MATCHES = 4096;

    private StorageSearch() {}

    public static String name(String componentJson) {
        try {
            Component component = Component.Serializer.fromJson(componentJson);
            return component == null ? "" : component.getString();
        } catch (RuntimeException invalidComponent) {
            return "";
        }
    }

    /** Space-separated tokens are ANDed. @tokens match only the registry namespace. */
    public static boolean matches(String query, String displayName, String itemId) {
        if (query == null || query.length() > MAX_QUERY) return false;
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return true;
        String name = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        String id = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        int separator = id.indexOf(':');
        String namespace = separator < 0 ? id : id.substring(0, separator);
        for (String token : normalized.split("(?U)\\s+")) {
            if (token.startsWith("@")) {
                if (token.length() == 1 || !namespace.contains(token.substring(1))) return false;
            } else if (!name.contains(token) && !id.contains(token)) return false;
        }
        return true;
    }
}
