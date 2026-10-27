package com.example.inventory.mapper;

import com.example.inventory.model.Product;
import com.example.inventory.model.ProductRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ProductMapper {
  private final JdbcTemplate jdbc;
  private final RowMapper<Product> rowMapper =
      (rs, row) ->
          new Product(
              rs.getLong("id"),
              rs.getString("name"),
              rs.getBigDecimal("price"),
              rs.getInt("stock"));

  public ProductMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Product insert(ProductRequest request) {
    return jdbc.queryForObject(
        "INSERT INTO products(name, price, stock) VALUES (?, ?, ?) RETURNING *",
        rowMapper,
        request.name(),
        request.price(),
        request.stock());
  }

  public List<Product> findAll() {
    return jdbc.query("SELECT * FROM products ORDER BY id", rowMapper);
  }

  public Optional<Product> findById(long id) {
    return jdbc.query("SELECT * FROM products WHERE id = ?", rowMapper, id).stream().findFirst();
  }

  public int update(long id, ProductRequest request) {
    return jdbc.update(
        "UPDATE products SET name = ?, price = ?, stock = ? WHERE id = ?",
        request.name(),
        request.price(),
        request.stock(),
        id);
  }

  public int delete(long id) {
    return jdbc.update("DELETE FROM products WHERE id = ?", id);
  }

  /**
   * SQLite has no SELECT ... FOR UPDATE syntax. This atomic conditional UPDATE takes SQLite's
   * RESERVED database write lock while changing the row, serializing competing writers. Keeping the
   * stock check in the same statement prevents overselling under concurrent requests.
   */
  public int reserveStock(long id, int quantity) {
    return jdbc.update(
        "UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?",
        quantity,
        id,
        quantity);
  }

  public int releaseStock(long id, int quantity) {
    return jdbc.update(
        "UPDATE products SET stock = stock + ? WHERE id = ? AND stock <= ?",
        quantity,
        id,
        Integer.MAX_VALUE - quantity);
  }
}
