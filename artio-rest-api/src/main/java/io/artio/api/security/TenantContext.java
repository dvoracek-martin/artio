package io.artio.api.security;

import java.util.UUID;

/** Request-scoped tenant identity. Always cleared at the end of the security filter chain. */
public final class TenantContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private TenantContext() { }
    public static void set(UUID tenantId) { CURRENT.set(tenantId); }
    public static UUID current() { return CURRENT.get(); }
    public static UUID requireCurrent() {
        UUID value=CURRENT.get(); if(value==null) throw new IllegalStateException("Authenticated tenant context is required"); return value;
    }
    public static void clear() { CURRENT.remove(); }
}
