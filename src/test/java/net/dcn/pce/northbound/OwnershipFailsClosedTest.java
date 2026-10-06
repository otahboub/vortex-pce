package net.dcn.pce.northbound;

import net.dcn.pce.config.ApiPrincipals;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unknown ownership must deny, not permit.
 *
 * <p>Ownership is recorded after the solve transaction commits, so a crash in between leaves a
 * live task with no recorded owner. It used to be treated as "anyone may act on this", which
 * meant a crash, a corrupt ownership file, or a single failed write silently converted every
 * tenant's tasks into shared tasks that any tenant-scoped operator could cancel.
 *
 * <p>The cost of failing closed is that a tenant may lose access to its own task and need an
 * administrator. That is recoverable; a cross-tenant cancellation is not.
 */
class OwnershipFailsClosedTest {

    private static final ApiPrincipals PRINCIPALS = ApiPrincipals.parse(
            "alpha:OPERATOR:tenant-a:key-a,root:ADMIN::key-root,global:OPERATOR::key-global", null);

    private static ApiPrincipals.Principal principal(String name) {
        return PRINCIPALS.identify(switch (name) {
            case "alpha" -> "key-a";
            case "root" -> "key-root";
            default -> "key-global";
        }).orElseThrow();
    }

    @Test
    void aTenantScopedOperatorIsDeniedAnUnownedTask() {
        assertFalse(principal("alpha").owns(null),
                "a task with no recorded owner must not be actionable by a tenant");
        assertTrue(principal("alpha").owns("tenant-a"), "its own tenant's task still is");
        assertFalse(principal("alpha").owns("tenant-b"));
    }

    @Test
    void anAdministratorStillReachesUnownedWork() {
        // Otherwise a crash between commit and claim would strand that capacity for ever.
        assertTrue(principal("root").owns(null));
        assertTrue(principal("root").owns("tenant-b"));
    }

    @Test
    void anUnscopedOperatorKeepsPreTenancyBehaviour() {
        // A principal configured with no tenant is deliberately global.
        assertTrue(principal("global").owns(null));
        assertTrue(principal("global").owns("tenant-a"));
    }

    @Test
    void unknownOwnershipIsWhatAnUnrecoverableRecordLooksLike() {
        // Ownership now commits with the reservation, so the corrupt-sidecar and failed-write
        // cases that used to live here cannot arise: there is no second file to lose. What
        // remains is a task whose owner is genuinely unknown -- one admitted before tenants
        // existed, or restored from a format 5 log -- and the rule for it is the same.
        assertFalse(principal("alpha").owns(null),
                "an unowned task must not be actionable by a tenant-scoped operator");
        assertTrue(principal("root").owns(null),
                "an administrator must still be able to clear it up");
    }
}
