package io.artio.api.catalog;

import java.util.ArrayList;
import java.util.List;

/** Small RFC-4180-compatible codec for the documented four-column catalog exchange format. */
final class ProductCsvCodec {
    private static final List<String> HEADERS = List.of("sku", "name", "description", "active");
    private ProductCsvCodec() { }

    static List<ImportRow> parse(String csv) {
        if (csv == null || csv.isBlank()) throw new IllegalArgumentException("CSV file is empty");
        if(csv.charAt(0)=='\uFEFF')csv=csv.substring(1);
        List<List<String>> records = records(csv);
        if (records.isEmpty() || !records.getFirst().equals(HEADERS))
            throw new IllegalArgumentException("CSV header must be exactly: sku,name,description,active");
        if (records.size() - 1 > 5_000) throw new IllegalArgumentException("CSV import is limited to 5000 product rows");
        var result = new ArrayList<ImportRow>();
        for (int i=1;i<records.size();i++) {
            var fields=records.get(i);
            if(fields.size()==1&&fields.getFirst().isBlank())continue;
            if(fields.size()!=4)throw new IllegalArgumentException("CSV row "+(i+1)+" must contain exactly four fields");
            result.add(new ImportRow(i+1,fields.get(0).trim(),fields.get(1).trim(),emptyToNull(fields.get(2)),parseActive(fields.get(3),i+1)));
        }
        return List.copyOf(result);
    }

    static String export(List<ProductResponse> products) {
        var out = new StringBuilder("sku,name,description,active\r\n");
        for (var product : products) {
            if (product.archived()) continue;
            out.append(cell(product.sku())).append(',').append(cell(product.name())).append(',')
                    .append(cell(product.description())).append(',').append(product.active()).append("\r\n");
        }
        return out.toString();
    }

    private static List<List<String>> records(String csv) {
        var rows=new ArrayList<List<String>>();var row=new ArrayList<String>();var field=new StringBuilder();
        boolean quoted=false,afterQuote=false,recordStarted=false;
        for(int i=0;i<csv.length();i++){
            char c=csv.charAt(i);
            if(quoted){
                if(c=='"'){
                    if(i+1<csv.length()&&csv.charAt(i+1)=='"'){field.append('"');i++;}
                    else{quoted=false;afterQuote=true;}
                }else field.append(c);
                recordStarted=true;continue;
            }
            if(afterQuote){
                if(c==','){row.add(field.toString());field.setLength(0);afterQuote=false;recordStarted=true;continue;}
                if(c=='\r'||c=='\n'){row.add(field.toString());field.setLength(0);afterQuote=false;rows.add(List.copyOf(row));row.clear();recordStarted=false;if(c=='\r'&&i+1<csv.length()&&csv.charAt(i+1)=='\n')i++;continue;}
                throw new IllegalArgumentException("Unexpected character after quoted CSV field");
            }
            if(c=='"'){
                if(field.length()!=0)throw new IllegalArgumentException("Quote must begin at the start of a CSV field");
                quoted=true;recordStarted=true;
            }else if(c==','){
                row.add(field.toString());field.setLength(0);recordStarted=true;
            }else if(c=='\r'||c=='\n'){
                row.add(field.toString());field.setLength(0);rows.add(List.copyOf(row));row.clear();recordStarted=false;if(c=='\r'&&i+1<csv.length()&&csv.charAt(i+1)=='\n')i++;
            }else{field.append(c);recordStarted=true;}
        }
        if(quoted)throw new IllegalArgumentException("CSV file ends inside a quoted field");
        if(recordStarted||afterQuote||!row.isEmpty()){row.add(field.toString());rows.add(List.copyOf(row));}
        return rows;
    }

    private static Boolean parseActive(String value,int row){
        if("true".equalsIgnoreCase(value.trim()))return true;
        if("false".equalsIgnoreCase(value.trim()))return false;
        throw new IllegalArgumentException("CSV row "+row+" active must be true or false");
    }
    private static String emptyToNull(String value){String trimmed=value.trim();return trimmed.isEmpty()?null:trimmed;}
    private static String cell(String value){
        if(value==null)return "";
        String safe=value;
        if(!safe.isEmpty()&&"=+-@\t\r\n".indexOf(safe.charAt(0))>=0)safe="'"+safe;
        return "\""+safe.replace("\"","\"\"")+"\"";
    }
    record ImportRow(int row,String sku,String name,String description,Boolean active) { }
}
