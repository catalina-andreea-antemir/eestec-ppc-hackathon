package ro.filegrid.security;

/**
 * The 5 roles (contract schema {@code Role}, matrix 4.4). In the JWT they appear in the {@code role} (string) and
 * {@code roles} (list) claims; in Spring Security they become {@code ROLE_<name>} authorities (e.g.
 * {@code hasRole("ADMIN")}).
 */
public enum Role {
    DISPATCH,
    FIELD,
    CONNECTIONS,
    METER_SERVICE,
    ADMIN
}
