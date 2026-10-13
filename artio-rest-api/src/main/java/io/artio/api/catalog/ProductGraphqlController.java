package io.artio.api.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Read-only GraphQL projection for catalog exploration; mutations remain in the authorized REST API. */
@Controller
public class ProductGraphqlController {
    private final JdbcTemplate jdbc;
    public ProductGraphqlController(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @QueryMapping
    public List<GraphqlProduct> products(@Argument String search,@Argument Integer first){
        int limit=first==null?20:first;if(limit<1||limit>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"first must be between 1 and 100");
        String term=search==null?"":search.trim();if(term.length()>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Search text is too long");
        return jdbc.query("SELECT id,name,description,sku,active FROM catalog_product WHERE active AND (?='' OR name ILIKE ?) ORDER BY name LIMIT ?",(rs,n)->new GraphqlProduct(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("description"),rs.getString("sku"),rs.getBoolean("active")),term,"%"+term+"%",limit);
    }

    @QueryMapping
    public GraphqlProduct product(@Argument String id){try{return jdbc.queryForObject("SELECT id,name,description,sku,active FROM catalog_product WHERE id=? AND active",(rs,n)->new GraphqlProduct(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("description"),rs.getString("sku"),rs.getBoolean("active")),UUID.fromString(id));}catch(org.springframework.dao.EmptyResultDataAccessException e){return null;}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid product id");}}

    @SchemaMapping(typeName="Product",field="variants")
    public List<GraphqlVariant> variants(GraphqlProduct product){return jdbc.query("SELECT id,product_id,name,sku,active FROM catalog_variant WHERE product_id=? AND active ORDER BY name",(rs,n)->new GraphqlVariant(rs.getObject("id",UUID.class),rs.getObject("product_id",UUID.class),rs.getString("name"),rs.getString("sku"),rs.getBoolean("active")),product.id());}

    public record GraphqlProduct(UUID id,String name,String description,String sku,boolean active){}
    public record GraphqlVariant(UUID id,UUID productId,String name,String sku,boolean active){}
}
