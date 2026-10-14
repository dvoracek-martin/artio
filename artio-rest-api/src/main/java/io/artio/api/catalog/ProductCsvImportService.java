package io.artio.api.catalog;

import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Applies each valid CSV row through the normal product application use cases and reports row errors. */
@Service
public class ProductCsvImportService {
    private final ProductApplicationService products;
    public ProductCsvImportService(ProductApplicationService products) { this.products = products; }

    public ImportReport importRows(List<ProductCsvCodec.ImportRow> rows) {
        int created=0,updated=0;
        var errors=new ArrayList<RowError>();
        for(var row:rows){
            try{
                if(row.sku().isBlank()||row.name().isBlank())throw new IllegalArgumentException("SKU and name are required");
                var current=products.findBySku(row.sku());
                if(current.isPresent()){
                    var product=current.get();
                    if(product.archived())throw new IllegalStateException("Archived products cannot be changed by import");
                    products.update(product.id(),new UpdateProductRequest(row.name(),row.description()));
                    if(product.active()!=row.active())products.setActive(product.id(),row.active());
                    updated++;
                }else{
                    var product=products.create(new ProductRequest(row.name(),row.description(),row.sku()));
                    if(!row.active())products.setActive(product.id(),false);
                    created++;
                }
            }catch(DataIntegrityViolationException duplicate){
                errors.add(new RowError(row.row(),row.sku(),"SKU conflicts with an existing catalog record"));
            }catch(RuntimeException invalid){
                String reason=invalid.getMessage()==null?invalid.getClass().getSimpleName():invalid.getMessage();
                errors.add(new RowError(row.row(),row.sku(),reason.length()>300?reason.substring(0,300):reason));
            }
        }
        return new ImportReport(rows.size(),created,updated,errors.size(),List.copyOf(errors));
    }

    public record RowError(int row,String sku,String reason) { }
    public record ImportReport(int rows,int created,int updated,int failed,List<RowError> errors) { }
}
