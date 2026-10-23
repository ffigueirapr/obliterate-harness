package com.obliterateharness.harness;

import java.util.Map;
import java.util.Set;

/**
 * Language-independent system keys the harness engine relies on. Types and relations carry a systemKey,
 * so the engine finds them whatever their (translated or renamed) name is.
 * Projects created before system keys existed are backfilled by name (see LEGACY_*).
 */
public final class Reserved {
    private Reserved() {}

    // node types
    public static final String TEMPLATE = "template";
    public static final String BEAT = "beat";
    public static final String THREAD = "thread";

    // relation types
    public static final String BEAT_OF = "beat_of";
    public static final String MAPS_TO_BEAT = "maps_to_beat";
    public static final String PLANTS = "plants";
    public static final String PAYS = "pays";
    public static final String ABOUT = "about";
    public static final String APPEARS_IN = "appears_in";
    public static final String LOCATED_IN = "located_in";

    public static final Set<String> TEMPLATE_KIT = Set.of(TEMPLATE, BEAT, BEAT_OF, MAPS_TO_BEAT);
    public static final Set<String> THREAD_KIT = Set.of(THREAD, PLANTS, PAYS, ABOUT);
    public static final Set<String> CAST_KIT = Set.of(APPEARS_IN, LOCATED_IN);

    /** Hook statuses considered closed, in any supported language. */
    public static final Set<String> CLOSED_THREAD = Set.of("pago", "abandonado", "paid", "abandoned", "closed");

    /** Names used before system keys existed (Portuguese and English packs), for backfilling. */
    public static final Map<String, String> LEGACY_NODE_TYPES = Map.of(
            "template", TEMPLATE, "beat", BEAT, "gancho", THREAD, "hook", THREAD);
    public static final Map<String, String> LEGACY_REL_TYPES = Map.ofEntries(
            Map.entry("beat_de", BEAT_OF), Map.entry("beat_of", BEAT_OF),
            Map.entry("mapeia_beat", MAPS_TO_BEAT), Map.entry("maps_to_beat", MAPS_TO_BEAT),
            Map.entry("planta", PLANTS), Map.entry("plants", PLANTS),
            Map.entry("paga", PAYS), Map.entry("pays_off", PAYS),
            Map.entry("sobre", ABOUT), Map.entry("about", ABOUT),
            Map.entry("aparece_em", APPEARS_IN), Map.entry("appears_in", APPEARS_IN),
            Map.entry("está_em", LOCATED_IN), Map.entry("located_in", LOCATED_IN));
}
