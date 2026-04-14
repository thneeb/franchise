package de.neebs.franchise.entity;

public class Score {
    private int influence;
    private int bonusTiles;
    private int money;
    private int income;

    public int getInfluence() { return influence; }
    public void setInfluence(int influence) { this.influence = influence; }

    public int getBonusTiles() { return bonusTiles; }
    public void setBonusTiles(int bonusTiles) { this.bonusTiles = bonusTiles; }

    public int getMoney() { return money; }
    public void setMoney(int money) { this.money = money; }

    public int getIncome() { return income; }
    public void setIncome(int income) { this.income = income; }

    public Score copy() {
        Score s = new Score();
        s.influence = this.influence;
        s.bonusTiles = this.bonusTiles;
        s.money = this.money;
        s.income = this.income;
        return s;
    }
}
