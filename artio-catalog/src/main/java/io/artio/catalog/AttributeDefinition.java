package io.artio.catalog;
import io.artio.kernel.Aggregate;
import java.util.Objects;
public final class AttributeDefinition extends Aggregate<AttributeDefinitionId> {
    public enum Type { TEXT, NUMBER, BOOLEAN }
    private final AttributeCode code; private String label; private final Type type; private final boolean required; private boolean active;
    private AttributeDefinition(AttributeDefinitionId id, AttributeCode code, String label, Type type, boolean required, boolean active) {
        super(id); this.code = Objects.requireNonNull(code); this.label = validLabel(label); this.type = Objects.requireNonNull(type); this.required = required; this.active = active;
    }
    public static AttributeDefinition create(AttributeDefinitionId id, AttributeCode code, String label, Type type, boolean required) { var def = new AttributeDefinition(id, code, label, type, required, true); def.recordEvent(new AttributeDefinitionCreated(id, code, type)); return def; }
    public static AttributeDefinition reconstitute(AttributeDefinitionId id, AttributeCode code, String label, Type type, boolean required, boolean active) { return new AttributeDefinition(id, code, label, type, required, active); }
    public void rename(String value) { value = validLabel(value); if (!label.equals(value)) { label = value; recordEvent(new AttributeDefinitionRenamed(getId(), value)); } }
    public void setActive(boolean value) { if (active != value) { active = value; recordEvent(new AttributeDefinitionActiveChanged(getId(), value)); } }
    public AttributeCode getCode() { return code; } public String getLabel() { return label; } public Type getType() { return type; } public boolean isRequired() { return required; } public boolean isActive() { return active; }
    private static String validLabel(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("Attribute label is required"); value = value.trim(); if (value.length() > 200) throw new IllegalArgumentException("Attribute label cannot exceed 200 characters"); return value; }
}
