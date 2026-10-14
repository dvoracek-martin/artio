package io.artio.api.catalog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/catalog/products")
public class ProductCsvController {
    private static final int MAX_UPLOAD_BYTES=5*1024*1024;
    private static final int MAX_EXPORT_ROWS=50_000;
    private final ProductCsvImportService importer;
    private final JdbcTemplate jdbc;

    public ProductCsvController(ProductCsvImportService importer,JdbcTemplate jdbc){this.importer=importer;this.jdbc=jdbc;}

    @PostMapping(value="/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProductCsvImportService.ImportReport importCsv(@RequestPart("file") MultipartFile file) throws IOException {
        if(file==null||file.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select a non-empty CSV file");
        if(file.getSize()>MAX_UPLOAD_BYTES)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"CSV import is limited to 5 MiB");
        String csv;
        try{csv=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.getBytes())).toString();}
        catch(CharacterCodingException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CSV file must use UTF-8",invalid);}
        return importer.importRows(ProductCsvCodec.parse(csv));
    }

    @GetMapping(value="/export",produces="text/csv")
    public ResponseEntity<byte[]> exportCsv(){
        List<ProductResponse> rows=jdbc.query("SELECT id,name,description,sku,active,created_at,updated_at,archived_at FROM catalog_product ORDER BY created_at DESC,id LIMIT ?",
                (rs,row)->new ProductResponse(rs.getObject("id",java.util.UUID.class),rs.getString("name"),rs.getString("description"),rs.getString("sku"),
                        rs.getBoolean("active"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),null,Set.of(),Map.of(),null,null,null,
                        rs.getTimestamp("archived_at")!=null,rs.getTimestamp("archived_at")==null?null:rs.getTimestamp("archived_at").toInstant()),MAX_EXPORT_ROWS+1);
        if(rows.size()>MAX_EXPORT_ROWS)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Catalog export is limited to 50000 products");
        byte[] body=ProductCsvCodec.export(rows).getBytes(StandardCharsets.UTF_8);
        var headers=new HttpHeaders();headers.setContentType(new MediaType("text","csv",StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename("artio-products.csv").build());
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
