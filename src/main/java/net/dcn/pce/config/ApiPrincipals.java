package net.dcn.pce.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Who is calling, and what they are allowed to do.
 *
 * <p>The API had one process-wide secret. Any holder could solve, inspect, cancel every task,
 * mutate link capacity and read the configuration — so a read-only monitoring scraper and the
 * system that owns production traffic held the same credential, and cancelling another team's LSP
 * was indistinguishable from cancelling your own. Rotating the key meant coordinating every
 * client at once.
 *
 * <p>A principal is a named credential with a role and a tenant. The role bounds what it may do;
 * the tenant bounds which tasks it may see and cancel. Several may overlap during rotation: add
 * the new credential, restart the supported single instance, move clients, remove the old one and
 * restart again. The overlap prevents client lockout; startup-loaded configuration does not make
 * those restarts zero-downtime.
 *
 * <p>Configured as {@code name:role:tenant:secret} entries. The legacy single key remains valid
 * and is treated as an unrestricted {@code ADMIN} with no tenant, so existing deployments keep
 * working unchanged — the alternative is a security improvement nobody can adopt without an
 * outage.
 */
public final class ApiPrincipals {

    /** What a credential may do. */
    public enum Role {
        /** Read-only: health, metrics, configuration, task state. */
        VIEWER,
        /** Everything VIEWER may do, plus solving and cancelling its own tenant's tasks. */
        OPERATOR,
        /** Everything, including capacity mutation and tasks belonging to any tenant. */
        ADMIN;

        public boolean canWrite() {
            return this != VIEWER;
        }

        public boolean canAdminister() {
            return this == ADMIN;
        }
    }

    /** One named credential. */
    public record Principal(String name, Role role, String tenant, byte[] secret) {

        /**
         * Whether this principal may act on a task owned by {@code owner}.
         *
         * <p>{@code owner} is null when no ownership is recorded — a task admitted before tenants
         * existed, one whose owner could not be persisted, or every task at once if the ownership
         * file was lost. This used to return true for all of those, which made the control fail
         * open: a crash between committing a solve and recording its owner, or a single corrupt
         * file, silently converted every tenant's tasks into shared tasks that any tenant-scoped
         * operator could cancel.
         *
         * <p>Unknown ownership is now denied to anyone scoped to a tenant. That is the direction
         * an authorization check has to fail in: the cost is that a tenant may lose access to its
         * own task and need an administrator, which is recoverable, against a cross-tenant
         * cancellation that is not.
         *
         * <p>An ADMIN still sees everything, including unowned work — someone has to be able to
         * clear up after a tenant that has gone away, and denying that would make stranded
         * capacity unrecoverable. A principal configured with no tenant is deliberately unscoped
         * and keeps the pre-tenancy behaviour.
         */
        public boolean owns(String owner) {
            if (role.canAdminister() || tenant == null || tenant.isBlank()) {
                return true;
            }
            return tenant.equals(owner);
        }
    }

    private final Map<String, Principal> byName = new LinkedHashMap<>();

    private ApiPrincipals(Map<String, Principal> principals) {
        byName.putAll(principals);
    }

    /**
     * Parses {@code name:role:tenant:secret} entries, plus the legacy single key.
     *
     * @param legacyKey the process-wide key, kept as an unrestricted ADMIN, or null
     */
    public static ApiPrincipals parse(String raw, String legacyKey) {
        Map<String, Principal> principals = new LinkedHashMap<>();
        Set<String> seenSecrets = new LinkedHashSet<>();
        if (legacyKey != null && !legacyKey.isBlank()) {
            principals.put("default", new Principal("default", Role.ADMIN, null,
                    legacyKey.getBytes(StandardCharsets.UTF_8)));
            seenSecrets.add(legacyKey);
        }
        if (raw == null || raw.isBlank()) {
            return new ApiPrincipals(principals);
        }

        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(":", 4);
            if (parts.length != 4) {
                throw new IllegalArgumentException(
                        "API principal entries are 'name:role:tenant:secret', found: "
                                + parts.length + " field(s)");
            }
            String name = parts[0].trim();
            String tenant = parts[2].trim();
            String secret = parts[3];
            Role role;
            try {
                role = Role.valueOf(parts[1].trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown API principal role '" + parts[1].trim()
                        + "'; expected one of VIEWER, OPERATOR, ADMIN");
            }
            if (name.isEmpty() || secret.isBlank()) {
                throw new IllegalArgumentException(
                        "API principal entries need a name and a secret: " + name);
            }
            if (!seenSecrets.add(secret)) {
                // Two principals sharing a secret cannot be told apart, so the audit trail and the
                // tenant check would both be fiction. Refuse rather than authenticate ambiguously.
                throw new IllegalArgumentException(
                        "Two API principals share a secret; they could not be distinguished");
            }
            if (principals.put(name, new Principal(name, role, tenant.isEmpty() ? null : tenant,
                    secret.getBytes(StandardCharsets.UTF_8))) != null) {
                throw new IllegalArgumentException("Duplicate API principal name: " + name);
            }
        }
        return new ApiPrincipals(principals);
    }

    /**
     * Identifies the caller, in constant time against every credential.
     *
     * <p>Every principal is compared even after a match, so the time taken does not reveal which
     * credential matched or how many exist.
     */
    public Optional<Principal> identify(String presented) {
        if (presented == null) {
            return Optional.empty();
        }
        byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);
        Principal matched = null;
        for (Principal principal : byName.values()) {
            if (MessageDigest.isEqual(candidate, principal.secret()) && matched == null) {
                matched = principal;
            }
        }
        return Optional.ofNullable(matched);
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    public int size() {
        return byName.size();
    }

    /** Names and roles, for the configuration endpoint. Never the secrets. */
    public Map<String, String> describe() {
        Map<String, String> described = new LinkedHashMap<>();
        byName.forEach((name, principal) -> described.put(name,
                principal.role() + (principal.tenant() == null ? "" : "@" + principal.tenant())));
        return described;
    }
}
