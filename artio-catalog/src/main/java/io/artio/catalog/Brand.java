package io.artio.catalog;
import io.artio.kernel.Aggregate;
import java.util.Objects;
public final class Brand extends Aggregate<BrandId> {
    private BrandName name;
    private String description;
    private boolean active;
    private Brand(BrandId id, BrandName name, String description, boolean active) {
        super(id); this.name = Objects.requireNonNull(name); if (description != null && description.length() > 2000) throw new IllegalArgumentException("Brand description cannot exceed 2000 characters"); this.description = description; this.active = active;
    }
    public static Brand create(BrandId id, BrandName name, String description) { var brand = new Brand(id, name, description, true); brand.recordEvent(new BrandCreated(id, name)); return brand; }
    public static Brand reconstitute(BrandId id, BrandName name, String description, boolean active) { return new Brand(id, name, description, active); }
    public void update(BrandName newName, String newDescription) { var updated = new Brand(getId(), newName, newDescription, active); if (!name.equals(newName) || !Objects.equals(description, newDescription)) { var oldName = name; name = updated.name; description = updated.description; recordEvent(new BrandUpdated(getId(), oldName, name)); } }
    public void setActive(boolean value) { if (active != value) { active = value; recordEvent(new BrandActiveChanged(getId(), value)); } }
    public BrandName getName() { return name; } public String getDescription() { return description; } public boolean isActive() { return active; }
}
