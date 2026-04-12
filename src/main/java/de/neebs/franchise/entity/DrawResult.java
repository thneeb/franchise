package de.neebs.franchise.entity;

import java.util.List;

public class DrawResult {

    private final DrawRecord draw;
    private final int income;
    private final List<String> influenceLog;

    public DrawResult(DrawRecord draw, int income, List<String> influenceLog) {
        this.draw = draw;
        this.income = income;
        this.influenceLog = influenceLog;
    }

    public DrawRecord getDraw() { return draw; }
    public int getIncome() { return income; }
    public List<String> getInfluenceLog() { return influenceLog; }
}
