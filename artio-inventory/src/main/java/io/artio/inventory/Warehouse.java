package io.artio.inventory;
import io.artio.kernel.Aggregate; import java.util.Objects;
public final class Warehouse extends Aggregate<WarehouseId> {
    private final String code; private String name; private String location; private boolean active;
    private Warehouse(WarehouseId id,String code,String name,String location,boolean active){super(id);this.code=validCode(code);this.name=valid(name,"Warehouse name",200);this.location=valid(location,"Warehouse location",300);this.active=active;}
    public static Warehouse create(WarehouseId id,String code,String name,String location){var w=new Warehouse(id,code,name,location,true);w.recordEvent(new WarehouseCreated(id,w.code,w.name));return w;}
    public static Warehouse reconstitute(WarehouseId id,String code,String name,String location,boolean active){return new Warehouse(id,code,name,location,active);}
    public void update(String name,String location){String n=valid(name,"Warehouse name",200),l=valid(location,"Warehouse location",300);if(!this.name.equals(n)||!this.location.equals(l)){this.name=n;this.location=l;recordEvent(new WarehouseUpdated(getId(),n,l));}}
    public void setActive(boolean value){if(active!=value){active=value;recordEvent(new WarehouseActiveChanged(getId(),value));}}
    public String getCode(){return code;} public String getName(){return name;} public String getLocation(){return location;} public boolean isActive(){return active;}
    private static String validCode(String v){if(v==null||!v.matches("[A-Z0-9][A-Z0-9_-]{1,31}"))throw new IllegalArgumentException("Warehouse code must be 2-32 uppercase letters, digits, '_' or '-'");return v;}
    private static String valid(String v,String field,int max){if(v==null||v.isBlank())throw new IllegalArgumentException(field+" is required");v=v.trim();if(v.length()>max)throw new IllegalArgumentException(field+" cannot exceed "+max+" characters");return v;}
}
