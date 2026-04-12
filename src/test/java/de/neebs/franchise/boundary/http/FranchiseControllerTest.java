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
    void evaluateNextPossibleDraws_duringInit_returnsAllUnoccupiedTowns() throws Exception {
        String gameId = createGame("RED", "BLUE");

        mockMvc.perform(get("/franchise/{gameId}/draws", gameId))
                .andExpect(status().isOk())
                // 22 small towns (size == 1) in City enum
                .andExpect(jsonPath("$", hasSize(22)));
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
        performInitDraw(gameId, "BLUE", "LAS_VEGAS");
        performInitDraw(gameId, "RED", "RENO");

        // Undo back to index 1 — keeps only draw 0 (BLUE's draw)
        mockMvc.perform(delete("/franchise/{gameId}/draws/{index}", gameId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialization").value(true))
                .andExpect(jsonPath("$.next").value("RED"));

        // LAS_VEGAS still occupied, RENO is empty again
        mockMvc.perform(get("/franchise/{gameId}", gameId))
                .andExpect(jsonPath("$.cities[?(@.city=='LAS_VEGAS')].branches[0]").value("BLUE"))
                // JSONPath filter returns array; RENO's first slot should be null (unoccupied)
                .andExpect(jsonPath("$.cities[?(@.city=='RENO')].branches[0]", contains(nullValue())));
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
}
