package ro.filegrid.security;

/**
 * Cele 5 roluri (contract: schema {@code Role}, matricea 4.4). În JWT apar în claim-urile {@code role} (șir) și
 * {@code roles} (listă); în Spring Security devin autoritățile {@code ROLE_<nume>} (de exemplu
 * {@code hasRole("ADMIN")}). Permisiunile pe endpoint-uri se aplică la C10.
 */
public enum Role {
    DISPATCH,
    FIELD,
    CONNECTIONS,
    METER_SERVICE,
    ADMIN
}
