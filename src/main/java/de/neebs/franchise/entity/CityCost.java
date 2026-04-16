package de.neebs.franchise.entity;

public class CityCost {

    private final City city;
    private final int cost;

    public CityCost(City city, int cost) {
        this.city = city;
        this.cost = cost;
    }

    public City getCity() {
        return city;
    }

    public int getCost() {
        return cost;
    }
}
