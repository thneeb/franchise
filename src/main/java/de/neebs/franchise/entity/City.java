package de.neebs.franchise.entity;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum City {
    SAN_FRANCISCO("San Francisco", 4),
    LOS_ANGELES("Los Angeles", 7),
    LAS_VEGAS("Las Vegas", 1),
    RENO("Reno", 1),
    FLAGSTAFF("Flagstaff", 1),
    PHOENIX("Phoenix", 5),
    ALBUQUERQUE("Albuquerque", 4),
    PUEBLO("Pueblo", 1),
    DENVER("Denver", 4),
    SALT_LAKE_CITY("Salt Lake City", 1),
    SEATTLE("Seattle", 4),
    PORTLAND("Portland", 2),
    SPOKANE("Spokane", 1),
    BOISE("Boise", 1),
    POCATELLO("Pocatello", 1),
    CONRAD("Conrad", 1),
    BILLINGS("Billings", 1),
    CASPER("Casper", 1),
    FARGO("Fargo", 1),
    SIOUX_FALLS("Sioux Falls", 1),
    MINNEAPOLIS("Minneapolis", 2),
    CHICAGO("Chicago", 7),
    DETROIT("Detroit", 4),
    INDIANAPOLIS("Indianapolis", 1),
    PITTSBURGH("Pittsburgh", 2),
    NEW_YORK("New York", 8),
    WASHINGTON("Washington", 6),
    CHARLOTTE("Charlotte", 3),
    RALEIGH("Raleigh", 1),
    CHARLESTON("Charleston", 3),
    JACKSONVILLE("Jacksonville", 2),
    ATLANTA("Atlanta", 5),
    MONTGOMERY("Montgomery", 1),
    HUNTSVILLE("Huntsville", 1),
    MEMPHIS("Memphis", 1),
    LITTLE_ROCK("Little Rock", 1),
    ST_LOUIS("St. Louis", 3),
    KANSAS_CITY("Kansas City", 5),
    OMAHA("Omaha", 4),
    OGALLALA("Ogallala", 1),
    DODGE_CITY("Dodge City", 1),
    HOUSTON("Houston", 6),
    DALLAS("Dallas", 5),
    OKLAHOMA_CITY("Oklahoma City", 4),
    EL_PASO("El Paso", 1);

    private final String name;
    private final int size;

    City(String name, int size) {
        this.name = name;
        this.size = size;
    }

    public String getName() { return name; }
    public int getSize() { return size; }

    @Override
    public String toString() { return name; }

    public static Set<City> getTowns() {
        return Arrays.stream(values()).filter(c -> c.getSize() == 1).collect(Collectors.toSet());
    }
}
