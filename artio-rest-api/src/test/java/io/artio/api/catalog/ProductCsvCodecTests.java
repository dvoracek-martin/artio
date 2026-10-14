package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductCsvCodecTests {
    @Test void readsQuotedCommasNewlinesAndDoubledQuotes() {
        var rows=ProductCsvCodec.parse("\uFEFFsku,name,description,active\r\nLMP-001,\"Lamp, brass\",\"First line\r\nSaid \"\"hello\"\"\",true\r\n");
        assertThat(rows).singleElement().satisfies(row->{
            assertThat(row.row()).isEqualTo(2);
            assertThat(row.name()).isEqualTo("Lamp, brass");
            assertThat(row.description()).isEqualTo("First line\r\nSaid \"hello\"");
            assertThat(row.active()).isTrue();
        });
    }

    @Test void rejectsMalformedQuotesHeadersAndStatus() {
        assertThatThrownBy(()->ProductCsvCodec.parse("sku,name,description\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("header");
        assertThatThrownBy(()->ProductCsvCodec.parse("sku,name,description,active\nLMP-001,\"open,desc,true\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quoted");
        assertThatThrownBy(()->ProductCsvCodec.parse("sku,name,description,active\nLMP-001,Lamp,,yes\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("true or false");
    }

    @Test void exportQuotesFieldsAndNeutralizesSpreadsheetFormulas() {
        var product=new ProductResponse(UUID.randomUUID(),"=2+2","line one\nline two","LMP-001",true,Instant.EPOCH,Instant.EPOCH,
                null,Set.of(),Map.of(),null,null,null,false,null);
        assertThat(ProductCsvCodec.export(java.util.List.of(product)))
                .contains("\"'=2+2\"")
                .contains("\"line one\nline two\"");
    }
}
