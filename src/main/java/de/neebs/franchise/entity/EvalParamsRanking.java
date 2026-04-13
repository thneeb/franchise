package de.neebs.franchise.entity;

public class EvalParamsRanking extends EvalParams {
    private int wins;
    private double winRate;

    public EvalParamsRanking() {}

    public EvalParamsRanking(int earlyIncomeWeight, int lateIncomeWeight, int wins, double winRate) {
        super(earlyIncomeWeight, lateIncomeWeight);
        this.wins = wins;
        this.winRate = winRate;
    }

    public int getWins() { return wins; }
    public void setWins(int wins) { this.wins = wins; }

    public double getWinRate() { return winRate; }
    public void setWinRate(double winRate) { this.winRate = winRate; }
}
