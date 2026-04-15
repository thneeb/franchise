package de.neebs.franchise.entity;

public class InfluenceEvent {

    private final int round;
    private final PlayerColor player;
    private final int points;
    private final String reason;

    public InfluenceEvent(int round, PlayerColor player, int points, String reason) {
        this.round = round;
        this.player = player;
        this.points = points;
        this.reason = reason;
    }

    public int getRound() { return round; }
    public PlayerColor getPlayer() { return player; }
    public int getPoints() { return points; }
    public String getReason() { return reason; }
}
