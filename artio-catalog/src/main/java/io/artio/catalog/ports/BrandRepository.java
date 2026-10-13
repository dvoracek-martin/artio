package io.artio.catalog.ports;
import io.artio.catalog.Brand; import io.artio.catalog.BrandId; import java.util.List; import java.util.Optional;
public interface BrandRepository { Optional<Brand> findById(BrandId id); List<Brand> findAll(); void save(Brand brand); }
