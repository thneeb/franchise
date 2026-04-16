package de.neebs.franchise.entity;

import java.util.List;

public class DrawCostSummary {

    private final int currentMoney;
    private final int income;
    private final int bonusMoney;
    private final int availableMoney;
    private final List<CityCost> extensionCosts;
    private final int increaseCost;
    private final int totalCost;
    private final String calculation;

    public DrawCostSummary(int currentMoney, int income, int bonusMoney, int availableMoney,
                           List<CityCost> extensionCosts, int increaseCost, int totalCost,
                           String calculation) {
        this.currentMoney = currentMoney;
        this.income = income;
        this.bonusMoney = bonusMoney;
        this.availableMoney = availableMoney;
        this.extensionCosts = extensionCosts;
        this.increaseCost = increaseCost;
        this.totalCost = totalCost;
        this.calculation = calculation;
    }

    public int getCurrentMoney() {
        return currentMoney;
    }

    public int getIncome() {
        return income;
    }

    public int getBonusMoney() {
        return bonusMoney;
    }

    public int getAvailableMoney() {
        return availableMoney;
    }

    public List<CityCost> getExtensionCosts() {
        return extensionCosts;
    }

    public int getIncreaseCost() {
        return increaseCost;
    }

    public int getTotalCost() {
        return totalCost;
    }

    public String getCalculation() {
        return calculation;
    }
}
