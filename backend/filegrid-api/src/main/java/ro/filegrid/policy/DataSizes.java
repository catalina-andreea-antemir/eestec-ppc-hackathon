package ro.filegrid.policy;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sizes in the policy YAML: a whole number of bytes or a number followed by a unit. Units are binary
 * (KB = KiB = 1024 bytes, MB = MiB, GB = GiB), matching the plan ("16 MiB chunk, 5 MiB minimum") and the contract
 * ({@code maxBytes: 20971520} for 20 MB).
 */
final class DataSizes {

    private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*([KMG]I?B|B)?", Pattern.CASE_INSENSITIVE);

    private DataSizes() {
    }

    static long parse(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("mărime invalidă: " + value);
        }
        Matcher m = SIZE.matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("mărime invalidă: „" + text + "” (exemple: 256KB, 20MB, 16MiB, 5GB)");
        }
        long number = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "B" : m.group(2).toUpperCase(Locale.ROOT);
        long multiplier = switch (unit.charAt(0)) {
            case 'K' -> 1L << 10;
            case 'M' -> 1L << 20;
            case 'G' -> 1L << 30;
            default -> 1L;
        };
        return Math.multiplyExact(number, multiplier);
    }
}
