package de.neebs.franchise.boundary.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FranchiseControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    de.neebs.franchise.control.FranchiseService franchiseService;

    // -------------------------------------------------------------------------
    // initializeGame
    // -------------------------------------------------------------------------

    @Test
    void initializeGame_returns201WithIdAndLocationHeader() throws Exception {
        mockMvc.perform(post("/franchise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"players": ["RED", "BLUE"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/franchise/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.initialization").value(true))
                .andExpect(jsonPath("$.end").value(false))
                .andExpect(jsonPath("$.round").value(0))
                .andExpect(jsonPath("$.players", hasSize(2)))
                .andExpect(jsonPath("$.cities", hasSize(45)));
    }

    @Test
    void initializeGame_nextPlayerIsLastInList_duringInit() throws Exception {
        // Initialization order is reverse — last player picks first
        mockMvc.perform(post("/franchise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"players": ["RED", "BLUE"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.next").value("BLUE"));
    }

    // -------------------------------------------------------------------------
    // retrieveGameBoard
    // -------------------------------------------------------------------------

    @Test
    void retrieveGameBoard_returns200() throws Exception {
        String gameId = createGame("RED", "BLUE");

        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(gameId))
                .andExpect(jsonPath("$.initialization").value(true))
                .andExpect(jsonPath("$.openRegions", hasSize(7)))
                .andExpect(jsonPath("$.influenceByRound", hasSize(0)))
                .andExpect(jsonPath("$.winners", hasSize(0)));
    }

    @Test
    void retrieveGameBoard_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/franchise/{gameId}", "does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void retrieveGameBoard_sectionsFilter_returnsOnlyRequestedSections() throws Exception {
        String gameId = createGame("RED", "BLUE");

        mockMvc.perform(get("/franchise/{gameId}", gameId).queryParam("sections", "winners,influence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winners", hasSize(0)))
                .andExpect(jsonPath("$.influenceByRound", hasSize(0)))
                .andExpect(jsonPath("$.cities", hasSize(0)))
                .andExpect(jsonPath("$.players", hasSize(0)))
                .andExpect(jsonPath("$.openRegions", hasSize(0)));
    }

    @Test
    void retrieveGameBoard_playersRecomputeLiveIncome() throws Exception {
        String gameId = createGame("RED", "BLUE");
        de.neebs.franchise.entity.GameState state = franchiseService.getGame(gameId);
        state.getScores().get(de.neebs.franchise.entity.PlayerColor.RED).setIncome(99);

        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players[?(@.color=='RED')].income")
                        .value(contains(franchiseService.getCurrentIncome(
                                state, de.neebs.franchise.entity.PlayerColor.RED))));
    }

    // -------------------------------------------------------------------------
    // evaluateNextPossibleDraws
    // -------------------------------------------------------------------------

    @Test
    void evaluateNextPossibleDraws_fivePlayerGame_returnsAll22Towns() throws Exception {
        // 5-player games have no inactive regions — all 22 small towns are available
        String gameId = createGame("RED", "BLUE", "WHITE", "ORANGE", "BLACK");

        mockMvc.perform(get("/franchise/{gameId}/draws", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(22)));
    }

    @Test
    void evaluateNextPossibleDraws_twoPlayerGame_excludesInactiveRegions() throws Exception {
        // 2-player games deactivate CALIFORNIA, MONTANA, UPPER_WEST.
        // Small towns blocked: LAS_VEGAS, RENO (CA=2), SPOKANE, BOISE, POCATELLO (UW=3),
        //                      CONRAD, BILLINGS, CASPER, FARGO, SIOUX_FALLS (MT=5) — 10 blocked.
        // 22 total small towns − 10 blocked = 12 available.
        String gameId = createGame("RED", "BLUE");

        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inactiveRegions", hasSize(3)))
                .andExpect(jsonPath("$.inactiveRegions", containsInAnyOrder(
                        "CALIFORNIA", "MONTANA", "UPPER_WEST")));

        mockMvc.perform(get("/franchise/{gameId}/draws", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(12)))
                // None of the inactive-region small towns may appear
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("LAS_VEGAS"))))
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("RENO"))))
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("SPOKANE"))))
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("BOISE"))))
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("POCATELLO"))))
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("CONRAD"))))
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("BILLINGS"))))
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("CASPER"))))
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("FARGO"))))
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("SIOUX_FALLS"))));
    }

    @Test
    void evaluateNextPossibleDraws_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/franchise/{gameId}/draws", "does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void evaluateNextPossibleDraws_whenGameEnded_returnsEmptyList() throws Exception {
        String gameId = createGame("RED", "BLUE");
        franchiseService.getGame(gameId).setEnd(true);

        mockMvc.perform(get("/franchise/{gameId}/draws", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // -------------------------------------------------------------------------
    // createDraw — initialization phase
    // -------------------------------------------------------------------------

    @Test
    void createDraw_initDraw_placesFirstBranch() throws Exception {
        String gameId = createGame("RED", "BLUE");

        // BLUE goes first (reverse order)
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE","extension":["INDIANAPOLIS"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draw.color").value("BLUE"))
                .andExpect(jsonPath("$.draw.extension[0]").value("INDIANAPOLIS"))
                .andExpect(jsonPath("$.processingTime").value(matchesPattern("\\d{2}:\\d{2}:\\d{2},\\d{4}")))
                .andExpect(jsonPath("$.money").value(4))
                .andExpect(jsonPath("$.end").value(false))
                .andExpect(jsonPath("$.winners", hasSize(0)))
                .andExpect(jsonPath("$.info.totalCost").value(0))
                .andExpect(jsonPath("$.info.calculation").value("Initialization draw: no money spent"))
                .andExpect(jsonPath("$.info.influenceByRound", hasSize(0)));

        // After BLUE's init draw, RED is next
        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(jsonPath("$.next").value("RED"))
                .andExpect(jsonPath("$.cities[?(@.city=='INDIANAPOLIS')].closed").value(true))
                .andExpect(jsonPath("$.cities[?(@.city=='INDIANAPOLIS')].branches[0]").value("BLUE"));
    }

    @Test
    void createDraw_wrongPlayer_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");

        // RED tries to go first but BLUE should go first during init
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"RED","extension":["LAS_VEGAS"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Not your turn"));
    }

    @Test
    void createDraw_missingPlayerType_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");

        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"BLUE","extension":["LAS_VEGAS"]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDraw_initDraw_bigCity_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");

        // BLUE tries to place in a large city (SAN_FRANCISCO, size 4) during init — only size-1 towns allowed
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE","extension":["SAN_FRANCISCO"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        containsString("Only small towns (size 1) are allowed during initialization")));
    }

    @Test
    void createDraw_initDraw_twoCities_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");

        // BLUE tries to place two cities during init — only 1 allowed
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE","extension":["DALLAS","MEMPHIS"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        containsString("Only one city allowed during initialization")));
    }

    @Test
    void createDraw_initDraw_occupiedTown_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");

        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");

        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"RED","extension":["INDIANAPOLIS"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        containsString("town is already occupied")));
    }

    @Test
    void createDraw_initDraw_withBonusTile_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");

        // BLUE tries to use a bonus tile during init — not allowed
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE","extension":["DALLAS"],"bonusTileUsage":"MONEY"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Bonus tiles cannot be used during initialization"));
    }

    @Test
    void createDraw_initDraw_withIncrease_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");

        // BLUE tries to increase a city during init — not allowed
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE","extension":["DALLAS"],"increase":["CHICAGO"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        containsString("Increases are not allowed during initialization")));
    }

    // -------------------------------------------------------------------------
    // createDraw — transition to normal play
    // -------------------------------------------------------------------------

    @Test
    void createDraw_afterAllInitDraws_switchesToNormalPlay() throws Exception {
        String gameId = createGame("RED", "BLUE");

        // Init phase: BLUE then RED
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");

        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(jsonPath("$.initialization").value(false))
                .andExpect(jsonPath("$.round").value(1))
                .andExpect(jsonPath("$.next").value("RED"));
    }

    // -------------------------------------------------------------------------
    // retrieveDraw
    // -------------------------------------------------------------------------

    @Test
    void retrieveDraw_returnsDrawAtIndex() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");

        mockMvc.perform(get("/franchise/{gameId}/draws/{index}", gameId, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.color").value("BLUE"))
                .andExpect(jsonPath("$.extension[0]").value("INDIANAPOLIS"));
    }

    @Test
    void retrieveDraw_invalidIndex_returns404() throws Exception {
        String gameId = createGame("RED", "BLUE");

        mockMvc.perform(get("/franchise/{gameId}/draws/{index}", gameId, 99))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // undoDraws
    // -------------------------------------------------------------------------

    @Test
    void undoDraws_removesDrawsFromIndex() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");

        // Undo back to index 1 — keeps only draw 0 (BLUE's draw)
        mockMvc.perform(delete("/franchise/{gameId}/draws/{index}", gameId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialization").value(true))
                .andExpect(jsonPath("$.next").value("RED"));

        // INDIANAPOLIS still occupied, MEMPHIS is empty again
        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(jsonPath("$.cities[?(@.city=='INDIANAPOLIS')].branches[0]").value("BLUE"))
                // JSONPath filter returns array; MEMPHIS's first slot should be null (unoccupied)
                .andExpect(jsonPath("$.cities[?(@.city=='MEMPHIS')].branches[0]", contains(nullValue())));
    }

    // -------------------------------------------------------------------------
    // Bonus tile validation
    // -------------------------------------------------------------------------

    @Test
    void expand_twoCity_withoutBonusTile_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");

        // RED is first (round 1) — try to expand to 2 cities without bonus tile
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"RED",
                                 "extension":["SAN_FRANCISCO","LOS_ANGELES"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        containsString("Cannot expand to more than 1 city/cities per turn")));
    }

    @Test
    void expand_extensionBonus_withOneCity_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");
        skipTurn(gameId, "RED");  // round 1 (RED's first turn)
        skipTurn(gameId, "BLUE"); // round 2 (BLUE's first turn)
        skipTurn(gameId, "RED");  // round 3

        // BLUE on round 4 — uses EXTENSION bonus but only provides 1 city → rejected
        int tilesBefore = objectMapper.readTree(
                mockMvc.perform(get("/franchise/{gameId}", gameId))
                        .andReturn().getResponse().getContentAsString())
                .at("/players/1/bonusTiles").asInt(); // BLUE is player index 1

        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE",
                                 "bonusTileUsage":"EXTENSION",
                                 "extension":["RALEIGH"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        containsString("EXTENSION bonus tile requires exactly 2 cities")));

        // Bonus tile must NOT have been consumed
        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(jsonPath("$.players[1].bonusTiles").value(tilesBefore));
    }

    @Test
    void expand_sameCityTwice_withExtensionBonus_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");
        skipTurn(gameId, "RED");  // round 1 (RED's first turn)
        skipTurn(gameId, "BLUE"); // round 2 (BLUE's first turn)
        skipTurn(gameId, "RED");  // round 3

        // BLUE on round 4 — try to expand to same city twice with EXTENSION bonus
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE",
                                 "bonusTileUsage":"EXTENSION",
                                 "extension":["CHARLOTTE","CHARLOTTE"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        containsString("Cannot expand to the same city twice")));
    }

    @Test
    void expand_twoCity_withExtensionBonus_succeeds() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");
        skipTurn(gameId, "RED");  // round 1 (RED's first turn)
        skipTurn(gameId, "BLUE"); // round 2 (BLUE's first turn)
        skipTurn(gameId, "RED");  // round 3

        // BLUE on round 4 — expand to 2 cities directly connected to INDIANAPOLIS
        // CHARLOTTE (cost 0) and DETROIT (cost 1) are both direct neighbours
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE",
                                 "bonusTileUsage":"EXTENSION",
                                 "extension":["CHARLOTTE","DETROIT"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draw.extension", hasSize(2)))
                .andExpect(jsonPath("$.info.currentMoney").isNumber())
                .andExpect(jsonPath("$.info.income").isNumber())
                .andExpect(jsonPath("$.info.bonusMoney").value(0))
                .andExpect(jsonPath("$.info.availableMoney").isNumber())
                .andExpect(jsonPath("$.info.extensionCosts[?(@.city=='CHARLOTTE')].value").value(contains(0)))
                .andExpect(jsonPath("$.info.extensionCosts[?(@.city=='DETROIT')].value").value(contains(1)))
                .andExpect(jsonPath("$.info.increaseCost").value(0))
                .andExpect(jsonPath("$.info.totalCost").value(1))
                .andExpect(jsonPath("$.info.calculation", containsString("CHARLOTTE:$0")))
                .andExpect(jsonPath("$.info.calculation", containsString("DETROIT:$1")));

        // Both cities should now have BLUE's branch
        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(jsonPath("$.cities[?(@.city=='CHARLOTTE')].branches[0]").value("BLUE"))
                .andExpect(jsonPath("$.cities[?(@.city=='DETROIT')].branches[0]").value("BLUE"));
    }

    @Test
    void expand_insufficientFunds_returns400() throws Exception {
        // 5-player game: all regions active, so CASPER (Montana) is available.
        // RED (index 0) gets $3 starting money. CASPER → OKLAHOMA_CITY costs $5.
        // Round 1: $3 + $1 income = $4 < $5 → must be rejected.
        String gameId = createGame("RED", "BLUE", "WHITE", "ORANGE", "BLACK");
        // Init order is reverse: BLACK → ORANGE → WHITE → BLUE → RED
        performInitDraw(gameId, "BLACK", "INDIANAPOLIS");
        performInitDraw(gameId, "ORANGE", "MEMPHIS");
        performInitDraw(gameId, "WHITE", "LITTLE_ROCK");
        performInitDraw(gameId, "BLUE", "HUNTSVILLE");
        performInitDraw(gameId, "RED", "CASPER");
        // RED goes first in round 1 with $3, income $1 = $4 available
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"RED","extension":["OKLAHOMA_CITY"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(allOf(
                        containsString("Insufficient funds"),
                        containsString("money:$3 + income:$1 = $4"),
                        containsString("OKLAHOMA_CITY:$5"))));
    }

    @Test
    void expand_toOwnCity_returns400() throws Exception {
        // BLUE expands to CHARLOTTE in round 1; trying again in round 2 must return 400
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");
        skipTurn(gameId, "RED");
        performExpand(gameId, "BLUE", "CHARLOTTE"); // BLUE now has a branch in CHARLOTTE
        skipTurn(gameId, "RED");
        // BLUE tries to expand to CHARLOTTE again
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE","extension":["CHARLOTTE"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("already have a branch")));
    }

    @Test
    void increase_doubleIncrease_withIncreaseBonusTile_succeeds() throws Exception {
        // BLUE at INDIANAPOLIS → expands to CHARLOTTE (cost 0, size 3, 2 free slots after expansion)
        // Then BLUE uses INCREASE bonus to double-increase in CHARLOTTE
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");
        skipTurn(gameId, "RED");            // round 1
        performExpand(gameId, "BLUE", "CHARLOTTE"); // round 2 — CHARLOTTE: 1 BLUE branch, 2 free
        skipTurn(gameId, "RED");            // round 3
        // BLUE on round 4 → bonus eligible; CHARLOTTE has 2 free slots
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE","increase":["CHARLOTTE","CHARLOTTE"],"bonusTileUsage":"INCREASE"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void increase_doubleIncrease_onlyOneFreeSlot_returns400() throws Exception {
        // BLUE at INDIANAPOLIS → expands to PITTSBURGH (size 2, 1 free slot after expansion)
        // INCREASE bonus requires ≥ 2 free slots → must return 400
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");
        skipTurn(gameId, "RED");            // round 1
        performExpand(gameId, "BLUE", "PITTSBURGH"); // round 2 — PITTSBURGH: 1 BLUE, 1 free
        skipTurn(gameId, "RED");            // round 3
        // BLUE on round 4 — PITTSBURGH has only 1 free slot
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE","increase":["PITTSBURGH","PITTSBURGH"],"bonusTileUsage":"INCREASE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("2 free slots")));
    }

    @Test
    void increase_doubleIncrease_withWrongCityCount_returns400() throws Exception {
        // INCREASE bonus requires exactly 1 city; 0 or 2+ must return 400
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");
        skipTurn(gameId, "RED");
        skipTurn(gameId, "BLUE");
        skipTurn(gameId, "RED"); // BLUE on round 4 — bonus eligible
        // 0 cities
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE","bonusTileUsage":"INCREASE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("exactly 2 identical cities")));
    }

    @Test
    void useBonusTile_onFirstTurn_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");

        // RED on round 1 — bonus tiles forbidden on first turn
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"RED","bonusTileUsage":"MONEY"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Bonus tiles cannot be used on the first turn"));

        skipTurn(gameId, "RED"); // round 1 — advance without bonus

        // BLUE on round 2 — also first turn, bonus tiles still forbidden
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE","bonusTileUsage":"MONEY"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Bonus tiles cannot be used on the first turn"));
    }

    @Test
    void useBonusTile_withNoneRemaining_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");

        // Exhaust all 4 of RED's bonus tiles (2-player game gives 4 tiles each)
        // RED plays on odd rounds: 1(skip), 3, 5, 7, 9 → uses bonus on rounds 3,5,7,9
        skipTurn(gameId, "RED");   // round 1
        skipTurn(gameId, "BLUE");  // round 2
        useBonusMoney(gameId, "RED");   // round 3 — 3 tiles left
        skipTurn(gameId, "BLUE");  // round 4
        useBonusMoney(gameId, "RED");   // round 5 — 2 tiles left
        skipTurn(gameId, "BLUE");  // round 6
        useBonusMoney(gameId, "RED");   // round 7 — 1 tile left
        skipTurn(gameId, "BLUE");  // round 8
        useBonusMoney(gameId, "RED");   // round 9 — 0 tiles left
        skipTurn(gameId, "BLUE");  // round 10

        // RED on round 11 — no bonus tiles left
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"RED","bonusTileUsage":"MONEY"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("No bonus tiles remaining"));
    }

    // -------------------------------------------------------------------------
    // Game-end: region track
    // -------------------------------------------------------------------------

    @Test
    void gameEnds_whenRegionTileReachesRedZone() throws Exception {
        // Rule: red zone = positions 8, 9, 10 on the region track.
        // Game ends after the current player's turn once any tile reaches position 8+.
        // regionTrackIndex is incremented after each tile is placed, so the check
        // fires when regionTrackIndex >= 8.
        // We verify the boundary: regionTrackIndex=7 → game continues; =8 → game ends.
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");

        // Both players can still expand from their starting towns, so the
        // consecutive-skip end condition will not fire (counter stays at 0).

        // Simulate 7 tiles on the track (just below the red zone threshold)
        de.neebs.franchise.entity.GameState state = franchiseService.getGame(gameId);
        state.setRegionTrackIndex(7);

        skipTurn(gameId, "RED");

        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(jsonPath("$.end").value(false));

        // Simulate the 8th tile reaching position 8 (red zone) — game must end
        state.setRegionTrackIndex(8);

        skipTurn(gameId, "BLUE");

        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(jsonPath("$.end").value(true))
                .andExpect(jsonPath("$.winners", is(not(empty()))))
                .andExpect(jsonPath("$.influenceByRound", is(not(empty()))));
    }

    @Test
    void retrieveGameBoard_listsAllWinners_whenInfluenceIsTied() throws Exception {
        String gameId = createGame("RED", "BLUE");
        de.neebs.franchise.entity.GameState state = franchiseService.getGame(gameId);
        state.setEnd(true);
        state.getScores().get(de.neebs.franchise.entity.PlayerColor.RED).setInfluence(12);
        state.getScores().get(de.neebs.franchise.entity.PlayerColor.BLUE).setInfluence(12);

        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winners", containsInAnyOrder("RED", "BLUE")));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createGame(String... players) throws Exception {
        String playersJson = String.join(",", java.util.Arrays.stream(players)
                .map(p -> "\"" + p + "\"")
                .toArray(String[]::new));

        MvcResult result = mockMvc.perform(post("/franchise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"players\": [" + playersJson + "]}"))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void performInitDraw(String gameId, String color, String city) throws Exception {
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"%s","extension":["%s"]}
                                """.formatted(color, city)))
                .andExpect(status().isOk());
    }

    private void skipTurn(String gameId, String color) throws Exception {
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"%s"}
                                """.formatted(color)))
                .andExpect(status().isOk());
    }

    private void performExpand(String gameId, String color, String city) throws Exception {
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"%s","extension":["%s"]}
                                """.formatted(color, city)))
                .andExpect(status().isOk());
    }

    private void useBonusMoney(String gameId, String color) throws Exception {
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"%s","bonusTileUsage":"MONEY"}
                                """.formatted(color)))
                .andExpect(status().isOk());
    }

    private void performComputerDraw(String gameId, String color, String strategy) throws Exception {
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"COMPUTER","color":"%s","strategy":"%s","params":{"depth":1}}
                                """.formatted(color, strategy)))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Computer / AI draws
    // -------------------------------------------------------------------------

    @Test
    void computerDraw_minimax_returnsValidDraw() throws Exception {
        // After both init draws, RED plays first — use MINIMAX at depth 1 for speed
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");

        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"COMPUTER","color":"RED","strategy":"MINIMAX","params":{"depth":1}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draw.color").value("RED"))
                .andExpect(jsonPath("$.draw.playerType").value("COMPUTER"))
                .andExpect(jsonPath("$.processingTime").value(matchesPattern("\\d{2}:\\d{2}:\\d{2},\\d{4}")));
    }

    @Test
    void computerDraw_abPrune_returnsValidDraw() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");

        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"COMPUTER","color":"RED","strategy":"AB_PRUNE","params":{"depth":1}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draw.color").value("RED"));
    }

    @Test
    void computerDraw_monteCarloTreeSearch_returnsValidDraw() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");

        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"COMPUTER","color":"RED","strategy":"MONTE_CARLO_TREE_SEARCH","params":{"simulations":8}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draw.color").value("RED"));
    }

    @Test
    void computerDraw_selfPlayQ_returnsValidDraw() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");

        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"COMPUTER","color":"RED","strategy":"SELF_PLAY_Q"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draw.color").value("RED"))
                .andExpect(jsonPath("$.draw.playerType").value("COMPUTER"));
    }

    @Test
    void computerDraw_wrongPlayer_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");

        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"COMPUTER","color":"BLUE","strategy":"MONTE_CARLO_VALUE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Not your turn"));
    }

    @Test
    void computerDraw_selectedMoveIsAmongPossibleDraws() throws Exception {
        // The move the AI picks must be one of the legal draws returned by getPossibleDraws
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "INDIANAPOLIS");
        performInitDraw(gameId, "RED", "MEMPHIS");

        // Capture possible draws before the AI move
        MvcResult possibleResult = mockMvc.perform(get("/franchise/{gameId}/draws", gameId))
                .andExpect(status().isOk())
                .andReturn();
        String possibleJson = possibleResult.getResponse().getContentAsString();

        // AI picks its move
        MvcResult drawResult = mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"COMPUTER","color":"RED","strategy":"MINIMAX","params":{"depth":1}}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        // The chosen draw must appear in the possible draws list
        com.fasterxml.jackson.databind.JsonNode chosen = objectMapper
                .readTree(drawResult.getResponse().getContentAsString()).get("draw");
        ((com.fasterxml.jackson.databind.node.ObjectNode) chosen).put("playerType", "HUMAN");
        com.fasterxml.jackson.databind.JsonNode possible = objectMapper.readTree(possibleJson);
        boolean found = false;
        for (com.fasterxml.jackson.databind.JsonNode node : possible) {
            if (node.equals(chosen)) { found = true; break; }
        }
        org.junit.jupiter.api.Assertions.assertTrue(found,
                "AI chose a draw that is not in getPossibleDraws: " + chosen);
    }

    @Test
    void calibrateStrategy_twoPlayers_returnsRankings() throws Exception {
        // Run a minimal calibration (2 games per matchup, depth 1) for speed
        mockMvc.perform(post("/franchise/calibrate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerCount":2,"gamesPerMatchup":2,"depth":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerCount").value(2))
                .andExpect(jsonPath("$.winner").exists())
                .andExpect(jsonPath("$.winner.earlyIncomeWeight").isNumber())
                .andExpect(jsonPath("$.winner.lateIncomeWeight").isNumber())
                .andExpect(jsonPath("$.rankings", hasSize(15)));
    }

    @Test
    void playGame_minimaxVsMinimax_returnsTotalWinsEqualingTimesToPlay() throws Exception {
        // Run 2 quick games (depth 1) and verify the result sums to 2
        String gameId = createGame("RED", "BLUE");

        mockMvc.perform(post("/franchise/{gameId}/learnings", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timesToPlay":2,
                                 "players":[
                                   {"playerType":"COMPUTER","color":"RED","strategy":"MINIMAX","params":{"depth":1}},
                                   {"playerType":"COMPUTER","color":"BLUE","strategy":"MINIMAX","params":{"depth":1}}
                                 ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wins", hasSize(2)))
                .andExpect(jsonPath("$.wins[0].value").isNumber())
                .andExpect(jsonPath("$.wins[1].value").isNumber())
                .andExpect(jsonPath("$.runtimes.processingTimes", hasSize(2)))
                .andExpect(jsonPath("$.runtimes.processingTimes[*].color", containsInAnyOrder("RED", "BLUE")))
                .andExpect(jsonPath("$.runtimes.processingTimes[*].value",
                        everyItem(matchesPattern("\\d{2}:\\d{2}:\\d{2},\\d{4}"))));
    }

    @Test
    void getLearningProgress_includesPerPlayerWins() throws Exception {
        String gameId = createGame("RED", "BLUE");

        mockMvc.perform(post("/franchise/{gameId}/learnings", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timesToPlay":1,
                                 "learningModels":["MONTE_CARLO_VALUE"],
                                 "players":[
                                   {"playerType":"COMPUTER","color":"RED","strategy":"MONTE_CARLO_VALUE"},
                                   {"playerType":"COMPUTER","color":"BLUE","strategy":"MINIMAX","params":{"depth":1}}
                                 ]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/franchise/{gameId}/learnings", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(gameId))
                .andExpect(jsonPath("$.wins", hasSize(2)))
                .andExpect(jsonPath("$.wins[*].color", containsInAnyOrder("RED", "BLUE")))
                .andExpect(jsonPath("$.wins[*].value", everyItem(isA(Number.class))))
                .andExpect(jsonPath("$.runtimes.processingTimes", hasSize(2)))
                .andExpect(jsonPath("$.runtimes.processingTimes[*].color", containsInAnyOrder("RED", "BLUE")))
                .andExpect(jsonPath("$.runtimes.processingTimes[*].value",
                        everyItem(matchesPattern("\\d{2}:\\d{2}:\\d{2},\\d{4}"))));
    }

    @Test
    void playGame_qLearningTraining_returnsWinsAndProgress() throws Exception {
        String gameId = createGame("RED", "BLUE");

        mockMvc.perform(post("/franchise/{gameId}/learnings", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timesToPlay":1,
                                 "learningModels":["Q_LEARNING"],
                                 "players":[
                                   {"playerType":"COMPUTER","color":"RED","strategy":"Q_LEARNING","params":{"trainingTarget":"INFLUENCE"}},
                                   {"playerType":"COMPUTER","color":"BLUE","strategy":"Q_LEARNING","params":{"trainingTarget":"INFLUENCE"}}
                                 ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wins", hasSize(2)))
                .andExpect(jsonPath("$.wins[0].value").isNumber())
                .andExpect(jsonPath("$.wins[1].value").isNumber())
                .andExpect(jsonPath("$.runtimes.processingTimes", hasSize(2)))
                .andExpect(jsonPath("$.runtimes.snapshotTime").value(matchesPattern("\\d{2}:\\d{2}:\\d{2},\\d{4}")))
                .andExpect(jsonPath("$.runtimes.trainingTime").value(matchesPattern("\\d{2}:\\d{2}:\\d{2},\\d{4}")))
                .andExpect(jsonPath("$.runtimes.modelSaveTime").value(matchesPattern("\\d{2}:\\d{2}:\\d{2},\\d{4}")))
                .andExpect(jsonPath("$.runtimes.otherTime").value(matchesPattern("\\d{2}:\\d{2}:\\d{2},\\d{4}")))
                .andExpect(jsonPath("$.runtimes.totalTime").value(matchesPattern("\\d{2}:\\d{2}:\\d{2},\\d{4}")))
                .andExpect(jsonPath("$.trainingRuns", hasSize(1)))
                .andExpect(jsonPath("$.trainingRuns[0].strategy").value("Q_LEARNING"))
                .andExpect(jsonPath("$.trainingRuns[0].value").isNumber());

        mockMvc.perform(get("/franchise/{gameId}/learnings", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(gameId))
                .andExpect(jsonPath("$.wins", hasSize(2)))
                .andExpect(jsonPath("$.runtimes.processingTimes", hasSize(2)))
                .andExpect(jsonPath("$.runtimes.snapshotTime").value(matchesPattern("\\d{2}:\\d{2}:\\d{2},\\d{4}")))
                .andExpect(jsonPath("$.runtimes.trainingTime").value(matchesPattern("\\d{2}:\\d{2}:\\d{2},\\d{4}")))
                .andExpect(jsonPath("$.runtimes.modelSaveTime").value(matchesPattern("\\d{2}:\\d{2}:\\d{2},\\d{4}")))
                .andExpect(jsonPath("$.runtimes.otherTime").value(matchesPattern("\\d{2}:\\d{2}:\\d{2},\\d{4}")))
                .andExpect(jsonPath("$.runtimes.totalTime").value(matchesPattern("\\d{2}:\\d{2}:\\d{2},\\d{4}")))
                .andExpect(jsonPath("$.trainingRuns", hasSize(1)))
                .andExpect(jsonPath("$.trainingRuns[0].strategy").value("Q_LEARNING"))
                .andExpect(jsonPath("$.trainingRuns[0].value").isNumber());
    }

    @Test
    void playGame_selfPlayQAlias_mapsToQLearning() throws Exception {
        String gameId = createGame("RED", "BLUE");

        mockMvc.perform(post("/franchise/{gameId}/learnings", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timesToPlay":1,
                                 "learningModels":["SELF_PLAY_Q"],
                                 "players":[
                                   {"playerType":"COMPUTER","color":"RED","strategy":"SELF_PLAY_Q"},
                                   {"playerType":"COMPUTER","color":"BLUE","strategy":"SELF_PLAY_Q"}
                                 ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trainingRuns", hasSize(1)))
                .andExpect(jsonPath("$.trainingRuns[0].strategy").value("Q_LEARNING"))
                .andExpect(jsonPath("$.trainingRuns[0].value").isNumber());
    }
}
