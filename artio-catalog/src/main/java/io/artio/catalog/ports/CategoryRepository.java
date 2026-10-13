package io.artio.catalog.ports;
import io.artio.catalog.Category; import io.artio.catalog.CategoryId; import java.util.List; import java.util.Optional;
public interface CategoryRepository { Optional<Category> findById(CategoryId id); List<Category> findAll(); void save(Category category); }
