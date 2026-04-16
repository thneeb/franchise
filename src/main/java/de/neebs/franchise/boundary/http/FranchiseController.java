package de.neebs.franchise.boundary.http;

import de.neebs.franchise.boundary.http.model.*;
import de.neebs.franchise.control.FranchiseService;
import de.neebs.franchise.entity.CalibrationConfig;
import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.DrawResult;
import de.neebs.franchise.entity.DurationFormatter;
import de.neebs.franchise.entity.EvalParams;
import de.neebs.franchise.entity.EvalParamsRanking;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.InfluenceEvent;
import de.neebs.franchise.entity.LearningRunResult;
import de.neebs.franchise.entity.Score;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        GameField gameField = toGameField(state, Set.of());
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, "/franchise/" + state.getId());
        return ResponseEntity.status(HttpStatus.CREATED).headers(headers).body(gameField);
    }

    @Override
    public ResponseEntity<GameField> retrieveGameBoard(String gameId, List<String> sections) {
        GameState state = franchiseService.getGame(gameId);
        return ResponseEntity.ok(toGameField(state, parseSections(sections)));
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
            return ResponseEntity.ok(toExtendedDraw(result, PlayerType.HUMAN));
        } else if (draw instanceof ComputerPlayer cp) {
            String strategyName = cp.getStrategy().name();
            Map<String, Object> params = cp.getParams() != null ? cp.getParams() : Map.of();
            de.neebs.franchise.entity.PlayerColor requestedPlayer =
                    de.neebs.franchise.entity.PlayerColor.valueOf(cp.getColor().name());
            DrawResult result = franchiseService.computeBestDraw(gameId, requestedPlayer, strategyName, params);
            return ResponseEntity.ok(toExtendedDraw(result, PlayerType.COMPUTER));
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
        return ResponseEntity.ok(toGameField(state, Set.of()));
    }

    @Override
    public ResponseEntity<LearningResult> playGame(String gameId, PlayConfig playConfig) {
        List<de.neebs.franchise.entity.PlayerColor> players = playConfig.getPlayers().stream()
                .map(cp -> de.neebs.franchise.entity.PlayerColor.valueOf(cp.getColor().name()))
                .collect(Collectors.toList());

        Map<de.neebs.franchise.entity.PlayerColor, String> strategies = playConfig.getPlayers().stream()
                .collect(Collectors.toMap(
                        cp -> de.neebs.franchise.entity.PlayerColor.valueOf(cp.getColor().name()),
                        cp -> cp.getStrategy().name()));

        // Per-player params override global params; fall back to global then empty map
        Map<String, Object> globalParams = playConfig.getParams() != null ? playConfig.getParams() : Map.of();
        Map<de.neebs.franchise.entity.PlayerColor, Map<String, Object>> playerParams =
                playConfig.getPlayers().stream()
                        .collect(Collectors.toMap(
                                cp -> de.neebs.franchise.entity.PlayerColor.valueOf(cp.getColor().name()),
                                cp -> cp.getParams() != null ? cp.getParams() : globalParams));

        int times = playConfig.getTimesToPlay() != null ? playConfig.getTimesToPlay() : 1;

        Set<String> learningModels = playConfig.getLearningModels() != null
                ? playConfig.getLearningModels().stream()
                        .map(ComputerStrategy::name)
                        .collect(Collectors.toSet())
                : Set.of();

        // Auto-inject epsilon=0.3 for any player whose strategy is being trained,
        // unless the caller has already set a value.
        if (!learningModels.isEmpty()) {
            playerParams = playerParams.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> {
                                String strat = strategies.get(e.getKey());
                                if (learningModels.contains(strat) && !e.getValue().containsKey("epsilon")) {
                                    Map<String, Object> merged = new java.util.LinkedHashMap<>(e.getValue());
                                    merged.put("epsilon", 0.3);
                                    return merged;
                                }
                                return e.getValue();
                            }));
        }

        String runId = learningModels.isEmpty() ? null : gameId;

        LearningRunResult result =
                franchiseService.runGames(players, strategies, playerParams, learningModels, runId, times);

        LearningResult model = new LearningResult()
                .wins(result.getWins().entrySet().stream()
                .map(e -> new PlayerColorAndInteger()
                        .color(PlayerColor.valueOf(e.getKey().name()))
                        .value(e.getValue()))
                .collect(Collectors.toList()))
                .processingTimes(toPlayerColorAndStringList(result.getProcessingTimeNanos()));
        return ResponseEntity.ok(model);
    }

    // -------------------------------------------------------------------------
    // Mapping: GameState → GameField
    // -------------------------------------------------------------------------

    private GameField toGameField(GameState state, Set<String> sections) {
        de.neebs.franchise.entity.PlayerColor currentPlayerColor =
                state.getPlayers().get(state.getCurrentPlayerIndex());
        Score currentScore = state.getScores().get(currentPlayerColor);

        boolean bonusTileUsable = !state.isInitialization()
                && state.getRound() > 1
                && currentScore.getBonusTiles() > 0;

        GameField gameField = new GameField()
                .id(state.getId())
                .end(state.isEnd())
                .initialization(state.isInitialization())
                .bonusTileUsable(bonusTileUsable)
                .round(state.getRound())
                .next(PlayerColor.valueOf(currentPlayerColor.name()));

        if (includeSection(sections, "cities")) {
            Map<de.neebs.franchise.entity.City, Integer> expansionCosts =
                    franchiseService.computeExpansionCosts(state.getId());
            List<CityPlate> cityPlates = Arrays.stream(de.neebs.franchise.entity.City.values())
                    .map(city -> toCityPlate(state, city, expansionCosts.get(city)))
                    .collect(Collectors.toList());
            gameField.setCities(cityPlates);
        }

        if (includeSection(sections, "players")) {
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
            gameField.setPlayers(players);
        }

        if (includeSection(sections, "regions")) {
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

            List<Region> openRegions = Arrays.stream(de.neebs.franchise.entity.Region.values())
                    .filter(r -> !state.getClosedRegions().contains(r))
                    .filter(r -> state.getInactiveRegions() == null || !state.getInactiveRegions().contains(r))
                    .map(r -> Region.valueOf(r.name()))
                    .toList();

            gameField.setFirstCities(firstCities);
            gameField.setClosedRegions(closedRegions);
            gameField.setInactiveRegions(inactiveRegions);
            gameField.setOpenRegions(openRegions);
        }

        if (includeSection(sections, "influence")) {
            gameField.setInfluenceByRound(toInfluenceRounds(state.getInfluenceHistory()));
        }

        if (includeSection(sections, "winners")) {
            gameField.setWinners(state.isEnd()
                    ? franchiseService.getWinners(state).stream()
                    .map(p -> PlayerColor.valueOf(p.name()))
                    .toList()
                    : List.of());
        }

        return gameField;
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

    private ExecutedDraw toExecutedDraw(DrawRecord record, PlayerType playerType) {
        ExecutedDraw draw = new ExecutedDraw();
        draw.setColor(PlayerColor.valueOf(record.getColor().name()));
        draw.setPlayerType(playerType);

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

    @Override
    public ResponseEntity<de.neebs.franchise.boundary.http.model.LearningProgress> getLearningProgress(String gameId) {
        de.neebs.franchise.entity.LearningProgress progress = franchiseService.getLearningProgress(gameId);
        if (progress == null) {
            return ResponseEntity.notFound().build();
        }
        de.neebs.franchise.boundary.http.model.LearningProgress model =
                new de.neebs.franchise.boundary.http.model.LearningProgress()
                        .runId(progress.getRunId())
                        .gamesCompleted(progress.getGamesCompleted())
                        .gamesTotal(progress.getGamesTotal())
                        .wins(progress.getWins().entrySet().stream()
                                .map(e -> new PlayerColorAndInteger()
                                        .color(PlayerColor.valueOf(e.getKey().name()))
                                        .value(e.getValue()))
                                .collect(Collectors.toList()))
                        .processingTimes(toPlayerColorAndStringList(progress.getProcessingTimeNanos()))
                        .done(progress.isDone());
        return ResponseEntity.ok(model);
    }

    @Override
    public ResponseEntity<CalibrationResult> calibrateStrategy(CalibrateConfig config) {
        int playerCount = config != null && config.getPlayerCount() != null ? config.getPlayerCount() : 2;
        int gamesPerMatchup = config != null && config.getGamesPerMatchup() != null ? config.getGamesPerMatchup() : 4;
        int depth = config != null && config.getDepth() != null ? config.getDepth() : 2;
        CalibrationConfig result = franchiseService.calibrate(playerCount, gamesPerMatchup, depth);
        return ResponseEntity.ok(toCalibrationResult(result));
    }

    // -------------------------------------------------------------------------
    // Mapping: DrawResult → ExtendedDraw
    // -------------------------------------------------------------------------

    private ExtendedDraw toExtendedDraw(DrawResult result, PlayerType playerType) {
        ExtendedDrawInfo info = new ExtendedDrawInfo()
                .income(result.getIncome())
                .influence(result.getInfluenceLog())
                .influenceByRound(toInfluenceRounds(result.getInfluenceEvents()));

        return new ExtendedDraw()
                .draw(toExecutedDraw(result.getDraw(), playerType))
                .info(info)
                .processingTime(DurationFormatter.formatNanos(result.getProcessingTimeNanos()))
                .money(result.getMoney())
                .end(result.isEnd())
                .winners(result.isEnd()
                        ? result.getWinners().stream()
                                .map(p -> PlayerColor.valueOf(p.name()))
                                .toList()
                        : List.of());
    }

    private List<InfluenceRound> toInfluenceRounds(List<InfluenceEvent> events) {
        Map<Integer, List<InfluenceEntry>> grouped = new LinkedHashMap<>();
        for (InfluenceEvent event : events) {
            grouped.computeIfAbsent(event.getRound(), ignored -> new ArrayList<>())
                    .add(new InfluenceEntry()
                            .player(PlayerColor.valueOf(event.getPlayer().name()))
                            .points(event.getPoints())
                            .reason(event.getReason()));
        }
        return grouped.entrySet().stream()
                .map(entry -> new InfluenceRound()
                        .round(entry.getKey())
                        .entries(entry.getValue()))
                .toList();
    }

    private List<PlayerColorAndString> toPlayerColorAndStringList(Map<de.neebs.franchise.entity.PlayerColor, Long> values) {
        return values.entrySet().stream()
                .map(e -> new PlayerColorAndString()
                        .color(PlayerColor.valueOf(e.getKey().name()))
                        .value(DurationFormatter.formatNanos(e.getValue())))
                .collect(Collectors.toList());
    }

    private Set<String> parseSections(List<String> sections) {
        if (sections == null || sections.isEmpty()) return Set.of();
        return sections.stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toSet());
    }

    private boolean includeSection(Set<String> sections, String section) {
        return sections.isEmpty() || sections.contains(section);
    }

    // -------------------------------------------------------------------------
    // Mapping: CalibrationConfig → CalibrationResult
    // -------------------------------------------------------------------------

    private CalibrationResult toCalibrationResult(CalibrationConfig config) {
        de.neebs.franchise.boundary.http.model.EvalParams winner =
                new de.neebs.franchise.boundary.http.model.EvalParams()
                        .earlyIncomeWeight(config.getWinner().getEarlyIncomeWeight())
                        .lateIncomeWeight(config.getWinner().getLateIncomeWeight());

        List<de.neebs.franchise.boundary.http.model.EvalParamsRanking> rankings =
                config.getRankings().stream()
                        .map(r -> new de.neebs.franchise.boundary.http.model.EvalParamsRanking()
                                .earlyIncomeWeight(r.getEarlyIncomeWeight())
                                .lateIncomeWeight(r.getLateIncomeWeight())
                                .wins(r.getWins())
                                .winRate(BigDecimal.valueOf(r.getWinRate())))
                        .collect(Collectors.toList());

        return new CalibrationResult()
                .playerCount(config.getPlayerCount())
                .calibratedAt(config.getCalibratedAt())
                .gamesPerMatchup(config.getGamesPerMatchup())
                .depth(config.getDepth())
                .winner(winner)
                .rankings(rankings);
    }
}
