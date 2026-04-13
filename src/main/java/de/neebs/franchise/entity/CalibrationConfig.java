package de.neebs.franchise.entity;

import java.util.List;

public class CalibrationConfig {
    private int playerCount;
    private String calibratedAt;
    private int gamesPerMatchup;
    private int depth;
    private EvalParams winner;
    private List<EvalParamsRanking> rankings;

    public int getPlayerCount() { return playerCount; }
    public void setPlayerCount(int playerCount) { this.playerCount = playerCount; }

    public String getCalibratedAt() { return calibratedAt; }
    public void setCalibratedAt(String calibratedAt) { this.calibratedAt = calibratedAt; }

    public int getGamesPerMatchup() { return gamesPerMatchup; }
    public void setGamesPerMatchup(int gamesPerMatchup) { this.gamesPerMatchup = gamesPerMatchup; }

    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }

    public EvalParams getWinner() { return winner; }
    public void setWinner(EvalParams winner) { this.winner = winner; }

    public List<EvalParamsRanking> getRankings() { return rankings; }
    public void setRankings(List<EvalParamsRanking> rankings) { this.rankings = rankings; }
}
