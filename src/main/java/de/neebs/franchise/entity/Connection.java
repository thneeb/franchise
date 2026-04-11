package de.neebs.franchise.entity;

import java.util.Set;

public record Connection(Set<City> cities, int cost) {}
