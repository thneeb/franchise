package de.neebs.franchise.control;

import de.neebs.franchise.entity.BonusTileUsage;
import de.neebs.franchise.entity.City;
import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;
import de.neebs.franchise.entity.Region;
import de.neebs.franchise.entity.Score;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelfPlayQStrategyTest {

    @Test
    void selectDraw_usesFullMoveSetIncludingExtensionBonusMoves() {
        FranchiseService franchiseService = mock(FranchiseService.class);
        SelfPlayQModelService modelService = mock(SelfPlayQModelService.class);
        NeuralNetwork network = mock(NeuralNetwork.class);
        SelfPlayQStrategy strategy = new SelfPlayQStrategy(franchiseService, modelService);

        GameState state = minimalState(PlayerColor.RED, PlayerColor.BLUE);

        DrawRecord extensionMove = new DrawRecord();
        extensionMove.setColor(PlayerColor.RED);
        extensionMove.setExtension(List.of(City.FLAGSTAFF, City.PUEBLO));
        extensionMove.setIncrease(List.of());
        extensionMove.setBonusTileUsage(BonusTileUsage.EXTENSION);

        when(franchiseService.getPossibleDrawsForState(state)).thenReturn(List.of(extensionMove));
        when(franchiseService.applyDrawOnState(state, extensionMove)).thenReturn(state);
        when(modelService.getOrCreate(2, QLearningTarget.TERMINAL_OUTCOME)).thenReturn(network);
        when(network.predictClamped(any())).thenReturn(1.0f);

        DrawRecord selected = strategy.selectDraw(state, PlayerColor.RED, Map.of());

        assertSame(extensionMove, selected);
        verify(franchiseService).getPossibleDrawsForState(state);
        verify(modelService).getOrCreate(2, QLearningTarget.TERMINAL_OUTCOME);
        verify(franchiseService).applyDrawOnState(state, extensionMove);
        verify(network).predictClamped(any());
    }

    private static GameState minimalState(PlayerColor... players) {
        GameState state = new GameState();
        state.setPlayers(List.of(players));
        state.setCurrentPlayerIndex(0);
        state.setRound(1);
        state.setRegionTrackIndex(0);
        state.setClosedCities(new HashSet<>());
        state.setClosedRegions(new java.util.ArrayList<>());
        state.setInactiveRegions(new java.util.ArrayList<>());
        state.setDrawHistory(new java.util.ArrayList<>());
        state.setInfluenceHistory(new java.util.ArrayList<>());
        state.setRegionFirstScorer(new EnumMap<>(Region.class));

        Map<PlayerColor, Score> scores = new EnumMap<>(PlayerColor.class);
        Map<PlayerColor, Integer> supply = new EnumMap<>(PlayerColor.class);
        for (PlayerColor player : players) {
            Score score = new Score();
            score.setMoney(0);
            score.setIncome(0);
            score.setInfluence(0);
            score.setBonusTiles(0);
            scores.put(player, score);
            supply.put(player, 40);
        }
        state.setScores(scores);
        state.setSupply(supply);

        Map<City, PlayerColor[]> cityBranches = new EnumMap<>(City.class);
        for (City city : City.values()) {
            cityBranches.put(city, new PlayerColor[city.getSize()]);
        }
        state.setCityBranches(cityBranches);
        return state;
    }
}
