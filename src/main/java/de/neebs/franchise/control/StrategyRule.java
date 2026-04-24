package de.neebs.franchise.control;

import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;

import java.util.List;

@FunctionalInterface
interface StrategyRule {
    /**
     * Filters the candidate move list. Must return the original list (not empty) as fallback
     * if no moves survive filtering, so the caller always has a valid set to pick from.
     */
    List<DrawRecord> filter(List<DrawRecord> moves, GameState state, PlayerColor player);
}
