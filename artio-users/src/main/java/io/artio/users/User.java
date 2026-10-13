package io.artio.users;

import io.artio.kernel.Aggregate;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public final class User extends Aggregate<UserId> {
    private final Email email;
    private final String passwordHash;
    private final String firstName;
    private final String lastName;
    private final UserRole role;
    private final java.util.EnumSet<Permission> permissions;
    private boolean emailVerified;
    private boolean active;
    private final Instant createdAt;
    private final Instant lastLogin;

    private User(UserId id, Email email, String passwordHash, String firstName, String lastName,
                 UserRole role, Set<Permission> permissions, boolean emailVerified, boolean active,
                 Instant createdAt, Instant lastLogin) {
        super(id);
        this.email = Objects.requireNonNull(email);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.firstName = Objects.requireNonNull(firstName);
        this.lastName = Objects.requireNonNull(lastName);
        this.role = Objects.requireNonNull(role);
        this.permissions = permissions.isEmpty() ? java.util.EnumSet.noneOf(Permission.class) : java.util.EnumSet.copyOf(permissions);
        this.emailVerified = emailVerified;
        this.active = active;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.lastLogin = lastLogin;
    }

    public static User registerCustomer(UserId id, Email email, String passwordHash, String firstName, String lastName) {
        if (firstName == null || firstName.isBlank() || firstName.length() > 255 || lastName == null || lastName.isBlank() || lastName.length() > 255)
            throw new IllegalArgumentException("First and last name are required");
        var user = new User(id, email, passwordHash, firstName.strip(), lastName.strip(), UserRole.CUSTOMER,
                Set.of(), false, true, Instant.now(), null);
        user.recordEvent(new UserRegistered(id, email));
        return user;
    }

    public static User restore(UserId id, Email email, String passwordHash, String firstName, String lastName,
                               UserRole role, Set<Permission> permissions, boolean verified, boolean active,
                               Instant createdAt, Instant lastLogin) {
        return new User(id, email, passwordHash, firstName, lastName, role, permissions, verified, active, createdAt, lastLogin);
    }

    public Email email() { return email; }
    public String passwordHash() { return passwordHash; }
    public String firstName() { return firstName; }
    public String lastName() { return lastName; }
    public UserRole role() { return role; }
    public Set<Permission> permissions() { return Set.copyOf(permissions); }
    public boolean emailVerified() { return emailVerified; }
    public boolean active() { return active; }
    public Instant createdAt() { return createdAt; }
    public Instant lastLogin() { return lastLogin; }
    public void verifyEmail() { if (!emailVerified) { emailVerified = true; recordEvent(new UserStateChanged(getId(), "emailVerified", "true")); } }
    public void deactivate() { if (active) { active = false; recordEvent(new UserStateChanged(getId(), "active", "false")); } }
    public void activate() { if (!active) { active = true; recordEvent(new UserStateChanged(getId(), "active", "true")); } }
    public void grantPermission(Permission permission) { if (permissions.add(java.util.Objects.requireNonNull(permission))) recordEvent(new UserStateChanged(getId(), "permission", "granted:" + permission.name())); }
    public void revokePermission(Permission permission) { if (permissions.remove(permission)) recordEvent(new UserStateChanged(getId(), "permission", "revoked:" + permission.name())); }
    public boolean hasPermission(Permission permission) { return permissions.contains(permission); }
}
