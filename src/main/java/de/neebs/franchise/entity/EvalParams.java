package de.neebs.franchise.entity;

public class EvalParams {
    private int earlyIncomeWeight;
    private int lateIncomeWeight;

    public EvalParams() {}

    public EvalParams(int earlyIncomeWeight, int lateIncomeWeight) {
        this.earlyIncomeWeight = earlyIncomeWeight;
        this.lateIncomeWeight = lateIncomeWeight;
    }

    public int getEarlyIncomeWeight() { return earlyIncomeWeight; }
    public void setEarlyIncomeWeight(int earlyIncomeWeight) { this.earlyIncomeWeight = earlyIncomeWeight; }

    public int getLateIncomeWeight() { return lateIncomeWeight; }
    public void setLateIncomeWeight(int lateIncomeWeight) { this.lateIncomeWeight = lateIncomeWeight; }
}
