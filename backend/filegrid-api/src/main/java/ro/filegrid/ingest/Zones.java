package ro.filegrid.ingest;

import java.util.Set;

/** Zonele permise (CHECK-urile din {@code outage_records} și {@code meter_readings}). */
public final class Zones {

    public static final Set<String> VALID = Set.of("Muntenia", "Banat", "Dobrogea");

    private Zones() {
    }
}
