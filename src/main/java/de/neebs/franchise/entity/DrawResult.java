package de.neebs.franchise.entity;

import java.util.List;

public class DrawResult {

    private final DrawRecord draw;
    private final int income;
    private final List<String> influenceLog;
    private final List<InfluenceEvent> influenceEvents;
    private final int money;
    private final boolean end;
    private final List<PlayerColor> winners;

    public DrawResult(DrawRecord draw, int income, List<String> influenceLog,
                      List<InfluenceEvent> influenceEvents, int money,
                      boolean end, List<PlayerColor> winners) {
        this.draw = draw;
        this.income = income;
        this.influenceLog = influenceLog;
        this.influenceEvents = influenceEvents;
        this.money = money;
        this.end = end;
        this.winners = winners;
    }

    public DrawRecord getDraw() { return draw; }
    public int getIncome() { return income; }
    public List<String> getInfluenceLog() { return influenceLog; }
    public List<InfluenceEvent> getInfluenceEvents() { return influenceEvents; }
    public int getMoney() { return money; }
    public boolean isEnd() { return end; }
    public List<PlayerColor> getWinners() { return winners; }
}
