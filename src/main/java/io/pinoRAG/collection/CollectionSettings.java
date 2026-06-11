package io.pinoRAG.collection;

import java.util.Map;

// Typed view over the pino_collections.settings JSONB column. Unknown keys
// are tolerated; missing keys fall back to safe defaults. Construct via
// CollectionSettings.of(settings) where settings is the raw map.
public record CollectionSettings(
        boolean piiScrubbingEnabled
) {

    public static CollectionSettings of(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return defaults();
        }
        Object pii = raw.get("piiScrubbingEnabled");
        boolean piiEnabled = (pii instanceof Boolean b) ? b : true;
        return new CollectionSettings(piiEnabled);
    }

    public static CollectionSettings defaults() {
        return new CollectionSettings(true);
    }
}
