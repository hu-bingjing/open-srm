package org.opensrm.foundation;

import java.util.List;
import java.util.UUID;

/** Full-tuple authorization primitive; organization persistence arrives in Phase 02. */
public final class ScopePolicy {
    private ScopePolicy() { }
    public record Scope(UUID company, UUID plant, UUID purchasingOrg, UUID supplier) { }
    public record Grant(String permission, UUID company, UUID plant, UUID purchasingOrg) { }
    public static boolean allows(String permission, Scope scope, List<Grant> grants, UUID boundSupplier) {
        if (boundSupplier != null && !boundSupplier.equals(scope.supplier())) return false;
        return grants.stream().anyMatch(g -> g.permission().equals(permission) && g.company().equals(scope.company())
            && (g.plant() == null || g.plant().equals(scope.plant()))
            && (g.purchasingOrg() == null || g.purchasingOrg().equals(scope.purchasingOrg())));
    }
}
