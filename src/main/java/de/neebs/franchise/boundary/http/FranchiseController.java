package de.neebs.franchise.boundary.http;

import de.neebs.franchise.boundary.http.model.*;
import de.neebs.franchise.control.FranchiseService;
import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.DrawResult;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.Score;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class FranchiseController implements FranchiseApi {

    private final FranchiseService franchiseService;

    public FranchiseController(FranchiseService franchiseService) {
        this.franchiseService = franchiseService;
    }

    @Override
    public ResponseEntity<GameField> initializeGame(GameConfig gameConfig) {
        List<de.neebs.franchise.entity.PlayerColor> players = gameConfig.getPlayers().stream()
                .map(p -> de.neebs.franchise.entity.PlayerColor.valueOf(p.name()))
                .collect(Collectors.toList());
        GameState state = franchiseService.initGame(players);
        GameField gameField = toGameField(state);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, "/franchise/" + state.getId());
        return ResponseEntity.status(HttpStatus.CREATED).headers(headers).body(gameField);
    }

    @Override
    public ResponseEntity<GameField> retrieveGameBoard(String gameId) {
        GameState state = franchiseService.getGame(gameId);
        return ResponseEntity.ok(toGameField(state));
    }

    @Override
    public ResponseEntity<List<HumanDraw>> evaluateNextPossibleDraws(String gameId) {
        List<DrawRecord> records = franchiseService.getPossibleDraws(gameId);
        List<HumanDraw> draws = records.stream()
                .map(this::toHumanDraw)
                .collect(Collectors.toList());
        return ResponseEntity.ok(draws);
    }

    @Override
    public ResponseEntity<ExtendedDraw> createDraw(String gameId, Draw draw) {
        if (draw instanceof HumanDraw humanDraw) {
            DrawRecord record = toDrawRecord(humanDraw);
            DrawResult result = franchiseService.applyDraw(gameId, record);
            return ResponseEntity.ok(toExtendedDraw(result));
        } else if (draw instanceof ComputerPlayer cp) {
            String strategyName = cp.getStrategy().name();
            Map<String, Object> params = cp.getParams() != null ? cp.getParams() : Map.of();
            DrawResult result = franchiseService.computeBestDraw(gameId, strategyName, params);
            return ResponseEntity.ok(toExtendedDraw(result));
        }
        return ResponseEntity.badRequest().build();
    }

    @Override
    public ResponseEntity<HumanDraw> retrieveDraw(String gameId, Integer index) {
        DrawRecord record = franchiseService.getDraw(gameId, index);
        return ResponseEntity.ok(toHumanDraw(record));
    }

    @Override
    public ResponseEntity<GameField> undoDraws(String gameId, Integer index) {
        GameState state = franchiseService.undoDraws(gameId, index);
        return ResponseEntity.ok(toGameField(state));
    }

    @Override
    public ResponseEntity<List<PlayerColorAndInteger>> playGame(String gameId, PlayConfig playConfig) {
        List<de.neebs.franchise.entity.PlayerColor> players = playConfig.getPlayers().stream()
                .map(cp -> de.neebs.franchise.entity.PlayerColor.valueOf(cp.getColor().name()))
                .collect(Collectors.toList());

        Map<de.neebs.franchise.entity.PlayerColor, String> strategies = playConfig.getPlayers().stream()
                .collect(Collectors.toMap(
                        cp -> de.neebs.franchise.entity.PlayerColor.valueOf(cp.getColor().name()),
                        cp -> cp.getStrategy().name()));

        Map<String, Object> params = playConfig.getParams() != null ? playConfig.getParams() : Map.of();
        int times = playConfig.getTimesToPlay() != null ? playConfig.getTimesToPlay() : 1;

        Map<de.neebs.franchise.entity.PlayerColor, Integer> wins =
                franchiseService.runGames(players, strategies, params, times);

        List<PlayerColorAndInteger> result = wins.entrySet().stream()
                .map(e -> new PlayerColorAndInteger()
                        .color(PlayerColor.valueOf(e.getKey().name()))
                        .value(e.getValue()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Mapping: GameState → GameField
    // -------------------------------------------------------------------------

    private GameField toGameField(GameState state) {
        de.neebs.franchise.entity.PlayerColor currentPlayerColor =
                state.getPlayers().get(state.getCurrentPlayerIndex());
        Score currentScore = state.getScores().get(currentPlayerColor);

        boolean bonusTileUsable = !state.isInitialization()
                && state.getRound() > 1
                && currentScore.getBonusTiles() > 0;

        Map<de.neebs.franchise.entity.City, Integer> expansionCosts =
                franchiseService.computeExpansionCosts(state.getId());

        List<CityPlate> cityPlates = Arrays.stream(de.neebs.franchise.entity.City.values())
                .map(city -> toCityPlate(state, city, expansionCosts.get(city)))
                .collect(Collectors.toList());

        List<Player> players = state.getPlayers().stream()
                .map(p -> {
                    Score s = state.getScores().get(p);
                    return new Player()
                            .color(PlayerColor.valueOf(p.name()))
                            .money(s.getMoney())
                            .influence(s.getInfluence())
                            .income(s.getIncome())
                            .bonusTiles(s.getBonusTiles());
                })
                .collect(Collectors.toList());

        List<PlayerRegion> firstCities = state.getRegionFirstScorer().entrySet().stream()
                .map(e -> new PlayerRegion()
                        .color(PlayerColor.valueOf(e.getValue().name()))
                        .region(Region.valueOf(e.getKey().name())))
                .collect(Collectors.toList());

        List<Region> closedRegions = state.getClosedRegions().stream()
                .filter(r -> state.getInactiveRegions() == null || !state.getInactiveRegions().contains(r))
                .map(r -> Region.valueOf(r.name()))
                .collect(Collectors.toList());

        List<Region> inactiveRegions = state.getInactiveRegions() == null ? List.of() :
                state.getInactiveRegions().stream()
                        .map(r -> Region.valueOf(r.name()))
                        .collect(Collectors.toList());

        return new GameField()
                .id(state.getId())
                .end(state.isEnd())
                .initialization(state.isInitialization())
                .bonusTileUsable(bonusTileUsable)
                .round(state.getRound())
                .next(PlayerColor.valueOf(currentPlayerColor.name()))
                .cities(cityPlates)
                .players(players)
                .firstCities(firstCities)
                .closedRegions(closedRegions)
                .inactiveRegions(inactiveRegions);
    }

    private CityPlate toCityPlate(GameState state, de.neebs.franchise.entity.City city,
                                   int extensionCost) {
        de.neebs.franchise.entity.PlayerColor[] slots = state.getCityBranches().get(city);
        List<PlayerColor> branches = Arrays.stream(slots)
                .map(s -> s != null ? PlayerColor.valueOf(s.name()) : null)
                .collect(Collectors.toList());

        return new CityPlate()
                .city(City.valueOf(city.name()))
                .size(city.getSize())
                .closed(state.getClosedCities().contains(city))
                .branches(branches)
                .extensionCosts(extensionCost);
    }

    // -------------------------------------------------------------------------
    // Mapping: DrawRecord ↔ HumanDraw
    // -------------------------------------------------------------------------

    private HumanDraw toHumanDraw(DrawRecord record) {
        HumanDraw draw = new HumanDraw();
        draw.setColor(PlayerColor.valueOf(record.getColor().name()));
        draw.setPlayerType(PlayerType.HUMAN);

        if (record.getExtension() != null) {
            draw.setExtension(record.getExtension().stream()
                    .map(c -> City.valueOf(c.name()))
                    .collect(Collectors.toList()));
        }

        if (record.getIncrease() != null) {
            draw.setIncrease(record.getIncrease().stream()
                    .map(c -> City.valueOf(c.name()))
                    .collect(Collectors.toList()));
        }

        if (record.getBonusTileUsage() != null) {
            draw.setBonusTileUsage(BonusTileUsage.valueOf(record.getBonusTileUsage().name()));
        }

        return draw;
    }

    private DrawRecord toDrawRecord(HumanDraw draw) {
        DrawRecord record = new DrawRecord();
        record.setColor(de.neebs.franchise.entity.PlayerColor.valueOf(draw.getColor().name()));

        record.setExtension(draw.getExtension() != null
                ? draw.getExtension().stream()
                        .map(c -> de.neebs.franchise.entity.City.valueOf(c.name()))
                        .collect(Collectors.toList())
                : List.of());

        record.setIncrease(draw.getIncrease() != null
                ? draw.getIncrease().stream()
                        .map(c -> de.neebs.franchise.entity.City.valueOf(c.name()))
                        .collect(Collectors.toList())
                : List.of());

        if (draw.getBonusTileUsage() != null) {
            record.setBonusTileUsage(
                    de.neebs.franchise.entity.BonusTileUsage.valueOf(draw.getBonusTileUsage().name()));
        }

        return record;
    }

    // -------------------------------------------------------------------------
    // Mapping: DrawResult → ExtendedDraw
    // -------------------------------------------------------------------------

    private ExtendedDraw toExtendedDraw(DrawResult result) {
        ExtendedDrawInfo info = new ExtendedDrawInfo()
                .income(result.getIncome())
                .influence(result.getInfluenceLog());

        return new ExtendedDraw()
                .draw(toHumanDraw(result.getDraw()))
                .info(info);
    }
}
