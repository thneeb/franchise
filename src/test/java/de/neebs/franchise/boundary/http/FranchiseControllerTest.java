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
                .andExpect(jsonPath("$.initialization").value(true));
    }

    @Test
    void retrieveGameBoard_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/franchise/{gameId}", "does-not-exist"))
                .andExpect(status().isNotFound());
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
        // 2-player games always deactivate CALIFORNIA, GRAND_CANYON, MONTANA.
        // Their small towns: LAS_VEGAS, RENO (CA), FLAGSTAFF, PUEBLO, SALT_LAKE_CITY (GC),
        //                    CONRAD, BILLINGS, CASPER, FARGO, SIOUX_FALLS (MT) — 10 towns blocked.
        // 22 total small towns − 10 blocked = 12 available.
        String gameId = createGame("RED", "BLUE");

        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inactiveRegions", hasSize(3)))
                .andExpect(jsonPath("$.inactiveRegions", containsInAnyOrder(
                        "CALIFORNIA", "GRAND_CANYON", "MONTANA")));

        mockMvc.perform(get("/franchise/{gameId}/draws", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(12)))
                // None of the inactive-region small towns may appear
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("LAS_VEGAS"))))
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("RENO"))))
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("FLAGSTAFF"))))
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("PUEBLO"))))
                .andExpect(jsonPath("$[*].extension[0]", not(hasItem("SALT_LAKE_CITY"))))
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
                                {"playerType":"HUMAN","color":"BLUE","extension":["LAS_VEGAS"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draw.color").value("BLUE"))
                .andExpect(jsonPath("$.draw.extension[0]").value("LAS_VEGAS"));

        // After BLUE's init draw, RED is next
        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(jsonPath("$.next").value("RED"))
                .andExpect(jsonPath("$.cities[?(@.city=='LAS_VEGAS')].branches[0]").value("BLUE"));
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

    // -------------------------------------------------------------------------
    // createDraw — transition to normal play
    // -------------------------------------------------------------------------

    @Test
    void createDraw_afterAllInitDraws_switchesToNormalPlay() throws Exception {
        String gameId = createGame("RED", "BLUE");

        // Init phase: BLUE then RED
        performInitDraw(gameId, "BLUE", "LAS_VEGAS");
        performInitDraw(gameId, "RED", "RENO");

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
        performInitDraw(gameId, "BLUE", "LAS_VEGAS");

        mockMvc.perform(get("/franchise/{gameId}/draws/{index}", gameId, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.color").value("BLUE"))
                .andExpect(jsonPath("$.extension[0]").value("LAS_VEGAS"));
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
        performInitDraw(gameId, "BLUE", "LAS_VEGAS");
        performInitDraw(gameId, "RED", "RENO");

        // RED is first (round 1) — try to expand to 2 cities without bonus tile
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"RED",
                                 "extension":["SAN_FRANCISCO","LOS_ANGELES"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        containsString("Cannot expand to more than 1")));
    }

    @Test
    void expand_sameCityTwice_withExtensionBonus_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "LAS_VEGAS");
        performInitDraw(gameId, "RED", "RENO");
        skipTurn(gameId, "RED"); // advance past round 1

        // BLUE on round 2 — try to expand to same city twice with EXTENSION bonus
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE",
                                 "bonusTileUsage":"EXTENSION",
                                 "extension":["FLAGSTAFF","FLAGSTAFF"]}
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
        skipTurn(gameId, "RED"); // advance past round 1

        // BLUE on round 2 — expand to 2 different active cities with EXTENSION bonus
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"BLUE",
                                 "bonusTileUsage":"EXTENSION",
                                 "extension":["RALEIGH","HUNTSVILLE"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draw.extension", hasSize(2)));

        // Both cities should now have BLUE's branch
        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(jsonPath("$.cities[?(@.city=='RALEIGH')].branches[0]").value("BLUE"))
                .andExpect(jsonPath("$.cities[?(@.city=='HUNTSVILLE')].branches[0]").value("BLUE"));
    }

    @Test
    void useBonusTile_onFirstTurn_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "LAS_VEGAS");
        performInitDraw(gameId, "RED", "RENO");

        // RED on round 1 — bonus tiles forbidden on first turn
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"RED","bonusTileUsage":"MONEY"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Bonus tiles cannot be used on the first turn"));
    }

    @Test
    void useBonusTile_withNoneRemaining_returns400() throws Exception {
        String gameId = createGame("RED", "BLUE");
        performInitDraw(gameId, "BLUE", "LAS_VEGAS");
        performInitDraw(gameId, "RED", "RENO");

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

    private void useBonusMoney(String gameId, String color) throws Exception {
        mockMvc.perform(post("/franchise/{gameId}/draws", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerType":"HUMAN","color":"%s","bonusTileUsage":"MONEY"}
                                """.formatted(color)))
                .andExpect(status().isOk());
    }
}
