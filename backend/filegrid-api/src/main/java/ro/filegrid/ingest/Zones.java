package ro.filegrid.ingest;

import java.util.Set;

/** Allowed zones (the CHECK constraints on {@code outage_records} and {@code meter_readings}). */
public final class Zones {

    public static final Set<String> VALID = Set.of("Muntenia", "Banat", "Dobrogea");

    private Zones() {
    }
}
