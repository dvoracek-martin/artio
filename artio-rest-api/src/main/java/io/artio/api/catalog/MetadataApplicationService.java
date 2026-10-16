package io.artio.api.catalog;

import io.artio.catalog.*;
import io.artio.catalog.ports.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates metadata use cases while domain invariants remain on the aggregates. */
@Service @Transactional
public class MetadataApplicationService {
    private final CategoryRepository categories; private final BrandRepository brands; private final AttributeDefinitionRepository attributes;
    public MetadataApplicationService(CategoryRepository categories, BrandRepository brands, AttributeDefinitionRepository attributes) { this.categories = categories; this.brands = brands; this.attributes = attributes; }
    @Transactional(readOnly=true) public List<CategoryView> categories() { return categories.findAll().stream().map(CategoryView::from).toList(); }
    public CategoryView createCategory(CategoryRequest r) { CategoryId parent = r.parentId() == null ? null : new CategoryId(r.parentId()); if (parent != null && categories.findById(parent).isEmpty()) throw new CatalogNotFoundException("Parent category not found: " + parent.value()); var c = Category.create(new CategoryId(UUID.randomUUID()), new CategoryName(r.name()), parent, r.description()); categories.save(c); return CategoryView.from(c); }
    public CategoryView updateCategory(UUID id, CategoryRequest r) { var c = categories.findById(new CategoryId(id)).orElseThrow(() -> new CatalogNotFoundException("Category not found: " + id)); CategoryId parent = r.parentId() == null ? null : new CategoryId(r.parentId()); if (parent != null) { var current = categories.findById(parent).orElseThrow(() -> new CatalogNotFoundException("Parent category not found: " + parent.value())); var seen = new HashSet<UUID>(); while (current != null && seen.add(current.getId().value())) { if (current.getId().equals(c.getId())) throw new IllegalArgumentException("Category hierarchy cannot contain a cycle"); var next = current.getParentId(); current = next == null ? null : categories.findById(next).orElse(null); } } c.rename(new CategoryName(r.name())); c.moveUnder(parent); c.setDescription(r.description()); c.setActive(r.active()); categories.save(c); return CategoryView.from(c); }
    @Transactional(readOnly=true) public List<BrandView> brands() { return brands.findAll().stream().map(BrandView::from).toList(); }
    public BrandView createBrand(BrandRequest r) { var b = Brand.create(new BrandId(UUID.randomUUID()), new BrandName(r.name()), r.description()); brands.save(b); return BrandView.from(b); }
    public BrandView updateBrand(UUID id, BrandRequest r) { var b = brands.findById(new BrandId(id)).orElseThrow(() -> new CatalogNotFoundException("Brand not found: " + id)); b.update(new BrandName(r.name()), r.description()); b.setActive(r.active()); brands.save(b); return BrandView.from(b); }
    @Transactional(readOnly=true) public List<AttributeView> attributes() { return attributes.findAll().stream().map(AttributeView::from).toList(); }
    public AttributeView createAttribute(AttributeRequest r) { var a = AttributeDefinition.create(new AttributeDefinitionId(UUID.randomUUID()), new AttributeCode(r.code()), r.label(), r.type(), r.required()); attributes.save(a); return AttributeView.from(a); }
    public AttributeView updateAttribute(UUID id, AttributeUpdateRequest r) { var a = attributes.findById(new AttributeDefinitionId(id)).orElseThrow(() -> new CatalogNotFoundException("Attribute definition not found: " + id)); a.rename(r.label()); a.setActive(r.active()); attributes.save(a); return AttributeView.from(a); }
    public record CategoryRequest(String name, UUID parentId, boolean active, String description) { public CategoryRequest(String name,UUID parentId,boolean active){this(name,parentId,active,"");} }
    public record BrandRequest(String name, String description, boolean active) {}
    public record AttributeRequest(String code, String label, AttributeDefinition.Type type, boolean required) {}
    public record AttributeUpdateRequest(String label, boolean active) {}
    public record CategoryView(UUID id, String name, UUID parentId, boolean active, String description) { static CategoryView from(Category c) { return new CategoryView(c.getId().value(), c.getName().value(), c.getParentId()==null?null:c.getParentId().value(), c.isActive(), c.getDescription()); } }
    public record BrandView(UUID id, String name, String description, boolean active) { static BrandView from(Brand b) { return new BrandView(b.getId().value(), b.getName().value(), b.getDescription(), b.isActive()); } }
    public record AttributeView(UUID id, String code, String label, AttributeDefinition.Type type, boolean required, boolean active) { static AttributeView from(AttributeDefinition a) { return new AttributeView(a.getId().value(), a.getCode().value(), a.getLabel(), a.getType(), a.isRequired(), a.isActive()); } }
}
