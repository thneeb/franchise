package de.neebs.franchise.entity;

import java.util.List;

public class DrawRecord {

    private PlayerColor color;
    private List<City> extension;
    private List<City> increase;
    private BonusTileUsage bonusTileUsage;
    private String reason;

    public PlayerColor getColor() { return color; }
    public void setColor(PlayerColor color) { this.color = color; }

    public List<City> getExtension() { return extension; }
    public void setExtension(List<City> extension) { this.extension = extension; }

    public List<City> getIncrease() { return increase; }
    public void setIncrease(List<City> increase) { this.increase = increase; }

    public BonusTileUsage getBonusTileUsage() { return bonusTileUsage; }
    public void setBonusTileUsage(BonusTileUsage bonusTileUsage) { this.bonusTileUsage = bonusTileUsage; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
