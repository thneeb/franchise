package de.neebs.franchise.entity;

import java.util.List;
import java.util.Set;

public enum Region {
    CALIFORNIA("California", 6, 4, 2, Set.of(City.SAN_FRANCISCO, City.LOS_ANGELES, City.LAS_VEGAS, City.RENO)),
    GRAND_CANYON("Grand Canyon", 10, 8, 5, Set.of(City.FLAGSTAFF, City.PHOENIX, City.ALBUQUERQUE, City.PUEBLO, City.DENVER, City.SALT_LAKE_CITY)),
    UPPER_WEST("Upper West", 7, 6, 1, Set.of(City.SEATTLE, City.PORTLAND, City.SPOKANE, City.BOISE, City.POCATELLO)),
    MONTANA("Montana", 8, 5, 4, Set.of(City.CONRAD, City.BILLINGS, City.CASPER, City.FARGO, City.SIOUX_FALLS)),
    GREAT_LAKES("Great Lakes", 6, 4, 2, Set.of(City.MINNEAPOLIS, City.CHICAGO, City.DETROIT, City.INDIANAPOLIS)),
    NEW_YORK("New York", 6, 5, 4, Set.of(City.PITTSBURGH, City.NEW_YORK)),
    WASHINGTON("Washington", 5, 3, 2, Set.of(City.WASHINGTON, City.CHARLOTTE, City.RALEIGH, City.CHARLESTON)),
    FLORIDA("Florida", 10, 6, 3, Set.of(City.JACKSONVILLE, City.ATLANTA, City.MONTGOMERY, City.HUNTSVILLE)),
    CENTRAL("Central", 4, 3, 1, Set.of(City.MEMPHIS, City.LITTLE_ROCK, City.ST_LOUIS, City.KANSAS_CITY, City.OMAHA, City.OGALLALA, City.DODGE_CITY)),
    TEXAS("Texas", 5, 4, 2, Set.of(City.HOUSTON, City.DALLAS, City.OKLAHOMA_CITY, City.EL_PASO));

    private final String name;
    private final int first;
    private final int second;
    private final int third;
    private final Set<City> cities;

    Region(String name, int first, int second, int third, Set<City> cities) {
        this.name = name;
        this.first = first;
        this.second = second;
        this.third = third;
        this.cities = cities;
    }

    public String getName() { return name; }
    public int getFirst() { return first; }
    public int getSecond() { return second; }
    public int getThird() { return third; }
    public Set<City> getCities() { return cities; }

    public int getByProfitLevel(int profitLevel) {
        if (profitLevel == 1) {
            return first;
        } else if (profitLevel == 2) {
            return second;
        } else {
            return third;
        }
    }

    public static List<Integer> getRegionFinishInfluence() {
        return List.of(3, 3, 2, 2, 3, 4, 3, 2, 0, 0);
    }
}
