package dev.simplesync.cloud;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Factory and registry for CloudProvider implementations.
 * Enables clean extensibility (Open/Closed Principle) for additional storage backends.
 */
public final class CloudProviderFactory {

    private static final Map<String, Supplier<CloudProvider>> REGISTRY = new ConcurrentHashMap<>();

    static {
        register("google_drive", GoogleDriveProvider::new);
    }

    private CloudProviderFactory() {}

    /**
     * Registers a new CloudProvider supplier under the given provider ID.
     */
    public static void register(String id, Supplier<CloudProvider> supplier) {
        if (id != null && supplier != null) {
            REGISTRY.put(id.toLowerCase(Locale.ROOT), supplier);
        }
    }

    /**
     * Creates or instantiates the CloudProvider for the specified provider ID.
     * Defaults to GoogleDriveProvider if the ID is unrecognized or null.
     */
    public static CloudProvider create(String id) {
        String key = (id == null || id.isBlank()) ? "google_drive" : id.toLowerCase(Locale.ROOT);
        Supplier<CloudProvider> supplier = REGISTRY.get(key);
        if (supplier != null) {
            return supplier.get();
        }
        return new GoogleDriveProvider();
    }
}
