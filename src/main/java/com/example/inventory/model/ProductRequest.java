package com.example.inventory.model;

import java.math.BigDecimal;

public record ProductRequest(String name, BigDecimal price, int stock) {}
