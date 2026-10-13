package io.artio.bundles;
import java.util.HashSet; import java.util.List; import java.util.Objects;
/** Customer-selectable group with a bounded selection count. */
public record BundleOptionGroup(String code,String name,int minimumSelections,int maximumSelections,List<BundleChoice> choices) {
    public BundleOptionGroup { if(code==null||!code.matches("[a-z][a-z0-9_-]{1,63}"))throw new IllegalArgumentException("Option group code must be lowercase alphanumeric with '_' or '-'");if(name==null||name.isBlank()||name.length()>200)throw new IllegalArgumentException("Option group name is required and limited to 200 characters");name=name.trim();Objects.requireNonNull(choices,"Choices are required");choices=List.copyOf(choices);if(choices.isEmpty()||minimumSelections<0||maximumSelections<minimumSelections||maximumSelections>choices.size())throw new IllegalArgumentException("Option selection bounds must fit the available choices");if(new HashSet<>(choices.stream().map(BundleChoice::sellableId).toList()).size()!=choices.size())throw new IllegalArgumentException("An option group cannot repeat a sellable"); }
}
