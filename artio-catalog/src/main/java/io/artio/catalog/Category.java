package io.artio.catalog;

import io.artio.kernel.Aggregate;
import java.util.Objects;

/** Hierarchical catalog classification. Parent references do not imply ownership of other aggregates. */
public final class Category extends Aggregate<CategoryId> {
    private CategoryName name;
    private CategoryId parentId;
    private boolean active;

    private Category(CategoryId id, CategoryName name, CategoryId parentId, boolean active) {
        super(id);
        this.name = Objects.requireNonNull(name);
        if (id.equals(parentId)) throw new IllegalArgumentException("Category cannot be its own parent");
        this.parentId = parentId;
        this.active = active;
    }

    public static Category create(CategoryId id, CategoryName name, CategoryId parentId) {
        var category = new Category(id, name, parentId, true);
        category.recordEvent(new CategoryCreated(id, name, parentId));
        return category;
    }

    public static Category reconstitute(CategoryId id, CategoryName name, CategoryId parentId, boolean active) {
        return new Category(id, name, parentId, active);
    }

    public void rename(CategoryName newName) {
        Objects.requireNonNull(newName);
        if (!newName.equals(name)) { var old = name; name = newName; recordEvent(new CategoryRenamed(getId(), old, newName)); }
    }
    public void moveUnder(CategoryId newParentId) {
        if (getId().equals(newParentId)) throw new IllegalArgumentException("Category cannot be its own parent");
        if (!Objects.equals(parentId, newParentId)) { var old = parentId; parentId = newParentId; recordEvent(new CategoryMoved(getId(), old, newParentId)); }
    }
    public void setActive(boolean value) { if (active != value) { active = value; recordEvent(new CategoryActiveChanged(getId(), value)); } }
    public CategoryName getName() { return name; }
    public CategoryId getParentId() { return parentId; }
    public boolean isActive() { return active; }
}
