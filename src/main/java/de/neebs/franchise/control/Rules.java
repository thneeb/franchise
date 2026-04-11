package de.neebs.franchise.control;

import de.neebs.franchise.entity.City;
import de.neebs.franchise.entity.Connection;
import de.neebs.franchise.entity.PlayerColor;
import de.neebs.franchise.entity.Score;

import java.util.*;

class Rules {
    public static final Set<Connection> CONNECTIONS = Set.of(
            new Connection(Set.of(City.SAN_FRANCISCO, City.LOS_ANGELES), 5),
            new Connection(Set.of(City.LOS_ANGELES, City.LAS_VEGAS), 1),
            new Connection(Set.of(City.SAN_FRANCISCO, City.RENO), 1),
            new Connection(Set.of(City.LOS_ANGELES, City.RENO), 1),
            new Connection(Set.of(City.PHOENIX, City.FLAGSTAFF), 1),
            new Connection(Set.of(City.PHOENIX, City.ALBUQUERQUE), 3),
            new Connection(Set.of(City.PHOENIX, City.DENVER), 5),
            new Connection(Set.of(City.PHOENIX, City.PUEBLO), 0),
            new Connection(Set.of(City.DENVER, City.PUEBLO), 1),
            new Connection(Set.of(City.OMAHA, City.DODGE_CITY), 0),
            new Connection(Set.of(City.OKLAHOMA_CITY, City.DODGE_CITY), 1),
            new Connection(Set.of(City.OKLAHOMA_CITY, City.CASPER), 5),
            new Connection(Set.of(City.FLAGSTAFF, City.SALT_LAKE_CITY), 0),
            new Connection(Set.of(City.SAN_FRANCISCO, City.SALT_LAKE_CITY), 1),
            new Connection(Set.of(City.FLAGSTAFF, City.SAN_FRANCISCO), 1),
            new Connection(Set.of(City.FLAGSTAFF, City.LAS_VEGAS), 0),
            new Connection(Set.of(City.ALBUQUERQUE, City.LAS_VEGAS), 1),
            new Connection(Set.of(City.PHOENIX, City.LOS_ANGELES), 5),
            new Connection(Set.of(City.ALBUQUERQUE, City.LOS_ANGELES), 8),
            new Connection(Set.of(City.ALBUQUERQUE, City.HOUSTON), 5),
            new Connection(Set.of(City.LOS_ANGELES, City.DENVER), 8),
            new Connection(Set.of(City.SEATTLE, City.PORTLAND), 3),
            new Connection(Set.of(City.SEATTLE, City.BOISE), 1),
            new Connection(Set.of(City.SEATTLE, City.SPOKANE), 0),
            new Connection(Set.of(City.POCATELLO, City.BOISE), 0),
            new Connection(Set.of(City.SPOKANE, City.BOISE), 0),
            new Connection(Set.of(City.SAN_FRANCISCO, City.PORTLAND), 3),
            new Connection(Set.of(City.SAN_FRANCISCO, City.SEATTLE), 5),
            new Connection(Set.of(City.SALT_LAKE_CITY, City.PORTLAND), 0),
            new Connection(Set.of(City.SALT_LAKE_CITY, City.POCATELLO), 0),
            new Connection(Set.of(City.DENVER, City.POCATELLO), 1),
            new Connection(Set.of(City.DENVER, City.SALT_LAKE_CITY), 1),
            new Connection(Set.of(City.CONRAD, City.BILLINGS), 0),
            new Connection(Set.of(City.BILLINGS, City.CASPER), 3),
            new Connection(Set.of(City.CASPER, City.FARGO), 1),
            new Connection(Set.of(City.BILLINGS, City.FARGO), 0),
            new Connection(Set.of(City.SIOUX_FALLS, City.FARGO), 1),
            new Connection(Set.of(City.SIOUX_FALLS, City.CASPER), 0),
            new Connection(Set.of(City.BILLINGS, City.SEATTLE), 3),
            new Connection(Set.of(City.SPOKANE, City.CONRAD), 1),
            new Connection(Set.of(City.SEATTLE, City.CASPER), 5),
            new Connection(Set.of(City.SEATTLE, City.CONRAD), 1),
            new Connection(Set.of(City.BOISE, City.CASPER), 1),
            new Connection(Set.of(City.CASPER, City.SAN_FRANCISCO), 8),
            new Connection(Set.of(City.CASPER, City.DENVER), 5),
            new Connection(Set.of(City.BILLINGS, City.MINNEAPOLIS), 3),
            new Connection(Set.of(City.FARGO, City.MINNEAPOLIS), 0),
            new Connection(Set.of(City.MINNEAPOLIS, City.CHICAGO), 3),
            new Connection(Set.of(City.CHICAGO, City.DETROIT), 3),
            new Connection(Set.of(City.CHICAGO, City.SIOUX_FALLS), 1),
            new Connection(Set.of(City.CHICAGO, City.PHOENIX), 8),
            new Connection(Set.of(City.DETROIT, City.INDIANAPOLIS), 1),
            new Connection(Set.of(City.CHICAGO, City.NEW_YORK), 8),
            new Connection(Set.of(City.OMAHA, City.HOUSTON), 8),
            new Connection(Set.of(City.OMAHA, City.CHICAGO), 5),
            new Connection(Set.of(City.KANSAS_CITY, City.OMAHA), 5),
            new Connection(Set.of(City.KANSAS_CITY, City.OKLAHOMA_CITY), 8),
            new Connection(Set.of(City.KANSAS_CITY, City.CHARLESTON), 8),
            new Connection(Set.of(City.HOUSTON, City.ATLANTA), 8),
            new Connection(Set.of(City.CHARLESTON, City.ATLANTA), 5),
            new Connection(Set.of(City.PITTSBURGH, City.NEW_YORK), 3),
            new Connection(Set.of(City.PITTSBURGH, City.MINNEAPOLIS), 3),
            new Connection(Set.of(City.PITTSBURGH, City.CHICAGO), 5),
            new Connection(Set.of(City.PITTSBURGH, City.INDIANAPOLIS), 1),
            new Connection(Set.of(City.CHARLOTTE, City.INDIANAPOLIS), 0),
            new Connection(Set.of(City.ST_LOUIS, City.INDIANAPOLIS), 1),
            new Connection(Set.of(City.DETROIT, City.NEW_YORK), 5),
            new Connection(Set.of(City.DETROIT, City.ST_LOUIS), 5),
            new Connection(Set.of(City.WASHINGTON, City.CHARLOTTE), 3),
            new Connection(Set.of(City.WASHINGTON, City.MEMPHIS), 1),
            new Connection(Set.of(City.ST_LOUIS, City.MEMPHIS), 0),
            new Connection(Set.of(City.WASHINGTON, City.KANSAS_CITY), 5),
            new Connection(Set.of(City.WASHINGTON, City.RALEIGH), 1),
            new Connection(Set.of(City.ATLANTA, City.RALEIGH), 0),
            new Connection(Set.of(City.ATLANTA, City.KANSAS_CITY), 5),
            new Connection(Set.of(City.ATLANTA, City.MONTGOMERY), 1),
            new Connection(Set.of(City.HUNTSVILLE, City.MONTGOMERY), 0),
            new Connection(Set.of(City.HUNTSVILLE, City.LITTLE_ROCK), 0),
            new Connection(Set.of(City.WASHINGTON, City.HUNTSVILLE), 1),
            new Connection(Set.of(City.CHARLESTON, City.RALEIGH), 1),
            new Connection(Set.of(City.CHARLESTON, City.CHARLOTTE), 3),
            new Connection(Set.of(City.NEW_YORK, City.CHARLOTTE), 5),
            new Connection(Set.of(City.PITTSBURGH, City.CHARLOTTE), 5),
            new Connection(Set.of(City.NEW_YORK, City.CHARLESTON), 5),
            new Connection(Set.of(City.PHOENIX, City.DALLAS), 5),
            new Connection(Set.of(City.NEW_YORK, City.ST_LOUIS), 5),
            new Connection(Set.of(City.CHARLOTTE, City.ST_LOUIS), 3),
            new Connection(Set.of(City.OGALLALA, City.CHICAGO), 1),
            new Connection(Set.of(City.OGALLALA, City.OMAHA), 1),
            new Connection(Set.of(City.OGALLALA, City.DODGE_CITY), 1),
            new Connection(Set.of(City.KANSAS_CITY, City.CHICAGO), 8),
            new Connection(Set.of(City.JACKSONVILLE, City.MONTGOMERY), 1),
            new Connection(Set.of(City.JACKSONVILLE, City.HOUSTON), 3),
            new Connection(Set.of(City.EL_PASO, City.ALBUQUERQUE), 0),
            new Connection(Set.of(City.EL_PASO, City.OKLAHOMA_CITY), 1),
            new Connection(Set.of(City.EL_PASO, City.DALLAS), 0),
            new Connection(Set.of(City.OKLAHOMA_CITY, City.DALLAS), 3),
            new Connection(Set.of(City.OKLAHOMA_CITY, City.PUEBLO), 1),
            new Connection(Set.of(City.OKLAHOMA_CITY, City.OGALLALA), 1),
            new Connection(Set.of(City.KANSAS_CITY, City.DALLAS), 5),
            new Connection(Set.of(City.ST_LOUIS, City.DALLAS), 5),
            new Connection(Set.of(City.HOUSTON, City.DALLAS), 5),
            new Connection(Set.of(City.HOUSTON, City.KANSAS_CITY), 5),
            new Connection(Set.of(City.MEMPHIS, City.KANSAS_CITY), 1),
            new Connection(Set.of(City.LITTLE_ROCK, City.KANSAS_CITY), 1),
            new Connection(Set.of(City.HOUSTON, City.LITTLE_ROCK), 1),
            new Connection(Set.of(City.OGALLALA, City.SIOUX_FALLS), 1)
    );

    private static final Map<Integer, MoneyMap> MONEY_MAP = Map.of(
            2, new MoneyMap(7)
                    .addEarn(0, 3, 1)
                    .addEarn(4, 10, 2)
                    .addEarn(11, 16, 3)
                    .addEarn(17, 22, 4)
                    .addEarn(23, 30, 5)
                    .addEarn(31, 39, 6)
                    .addInitial(2).addInitial(4),
            3, new MoneyMap(7)
                    .addEarn(0, 2, 1)
                    .addEarn(3, 6, 2)
                    .addEarn(7, 10, 3)
                    .addEarn(11, 14, 4)
                    .addEarn(15, 20, 5)
                    .addEarn(21, 26, 6)
                    .addInitial(2).addInitial(3).addInitial(5),
            4, new MoneyMap(7)
                    .addEarn(0, 1, 1)
                    .addEarn(2, 5, 2)
                    .addEarn(6, 9, 3)
                    .addEarn(10, 13, 4)
                    .addEarn(14, 17, 5)
                    .addEarn(18, 21, 6)
                    .addInitial(3).addInitial(4).addInitial(6).addInitial(8),
            5, new MoneyMap(7)
                    .addEarn(0, 1, 1)
                    .addEarn(2, 4, 2)
                    .addEarn(5, 8, 3)
                    .addEarn(9, 12, 4)
                    .addEarn(13, 15, 5)
                    .addEarn(16, 18, 6)
                    .addInitial(3).addInitial(4).addInitial(5).addInitial(7).addInitial(9)
    );

    public static Map<PlayerColor, Score> initScores(List<PlayerColor> players) {
        MoneyMap moneyMap = MONEY_MAP.get(players.size());
        Map<PlayerColor, Score> scores = new EnumMap<>(PlayerColor.class);
        for (int i = 0; i < players.size() && i < moneyMap.getInitialMoney().size(); i++) {
            Score score = new Score();
            score.setInfluence(0);
            score.setBonusTiles(players.size() == 2 ? 4 : 3);
            score.setMoney(moneyMap.getInitialMoney().get(i));
            score.setIncome(1);
            scores.put(players.get(i), score);
        }
        return scores;
    }

    private Rules() {}

    public static int calcIncome(int playerCount, int freeBranches) {
        return MONEY_MAP.get(playerCount).getMoneyByScore(freeBranches);
    }

    private static class MoneyMap {
        private final Map<Integer, Integer> earn = new HashMap<>();
        private final List<Integer> initial = new ArrayList<>();
        private final int max;

        public MoneyMap(int max) {
            this.max = max;
        }

        public MoneyMap addEarn(int lowerScore, int upperScore, int money) {
            for (int i = lowerScore; i <= upperScore; i++) {
                earn.put(i, money);
            }
            return this;
        }

        public MoneyMap addInitial(int money) {
            initial.add(money);
            return this;
        }

        public List<Integer> getInitialMoney() {
            return initial;
        }

        public int getMoneyByScore(int score) {
            Integer money = earn.get(score);
            return Objects.requireNonNullElse(money, max);
        }
    }
}
