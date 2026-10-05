package app.sprout.depository;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The depository on a real Postgres: accounts, settlement transfers and their rules. */
@Testcontainers
@SpringBootTest(properties = "spring.config.name=depository")
@AutoConfigureMockMvc
class DepositoryApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static final String DP = "dev-only-participant-key";
    static final String CC = "dev-only-depository-clearing-key";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=depository");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-06T04:00:00Z"));
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.DEPOSITORY_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    String bo;
    String settlement;

    @BeforeEach
    void aClientWithADematAccount() throws Exception {
        bo = open(UUID.randomUUID().toString()).path("boId").asText();
        settlement = "scc:" + UUID.randomUUID();
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    JsonNode open(String clientRef) throws Exception {
        return body(mvc.perform(post("/participant/v1/accounts").header("X-Participant-Key", DP).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("clientRef", clientRef, "holderName", "Meera Iyer")))));
    }

    ResultActions transfer(String instruction, String kind, String boId, String symbol, long qty) throws Exception {
        return mvc.perform(post("/clearing/v1/transfers").header("X-Clearing-Key", CC).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("instructionId", instruction, "kind", kind, "boId", boId, "symbol", symbol,
                        "quantity", qty, "settlementRef", settlement))));
    }

    long held(String boId, String symbol) throws Exception {
        for (JsonNode h : body(mvc.perform(get("/participant/v1/accounts/" + boId + "/holdings").header("X-Participant-Key", DP))
                .andExpect(MATCHES_CONTRACT)).path("holdings")) {
            if (h.path("symbol").asText().equals(symbol)) {
                return h.path("quantity").asLong();
            }
        }
        return 0;
    }

    @Test
    void aParticipantOpensOneDematAccountPerClientWithA16DigitId() throws Exception {
        String ref = UUID.randomUUID().toString();
        String id = body(mvc.perform(post("/participant/v1/accounts").header("X-Participant-Key", DP).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("clientRef", ref, "holderName", "Ravi Kumar"))))
                .andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT)).path("boId").asText();
        assertThat(id).matches("12081600[0-9]{8}");
        mvc.perform(post("/participant/v1/accounts").header("X-Participant-Key", DP).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("clientRef", ref, "holderName", "Ravi Kumar"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.boId").value(id));
        mvc.perform(get("/participant/v1/accounts/" + id).header("X-Participant-Key", DP)).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT);
        mvc.perform(get("/participant/v1/accounts/" + id).header("X-Participant-Key", "nope")).andExpect(status().isUnauthorized());
        mvc.perform(get("/participant/v1/accounts/1200000000000001").header("X-Participant-Key", DP))
                .andExpect(status().isNotFound()).andExpect(MATCHES_CONTRACT);
    }

    @Test
    void settlementDeliversToBuyersAndTakesFromSellersExactlyOnce() throws Exception {
        String buy = "payout:" + UUID.randomUUID();
        transfer(buy, "PAY_OUT", bo, "HARBOR", 10).andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT);
        transfer(buy, "PAY_OUT", bo, "HARBOR", 10).andExpect(status().isOk());              // repeated: once
        assertThat(held(bo, "HARBOR")).isEqualTo(10);
        transfer(buy, "PAY_OUT", bo, "HARBOR", 11).andExpect(status().isConflict()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("INSTRUCTION_CONFLICT"));
        transfer("payin:" + UUID.randomUUID(), "PAY_IN", bo, "HARBOR", 4).andExpect(status().isCreated());
        assertThat(held(bo, "HARBOR")).isEqualTo(6);
        JsonNode moves = body(mvc.perform(get("/participant/v1/accounts/" + bo + "/transactions").header("X-Participant-Key", DP))
                .andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)).path("transactions");
        assertThat(moves.size()).isEqualTo(2);
        assertThat(moves.get(0).path("quantity").asLong()).isEqualTo(-4);
        assertThat(moves.get(0).path("balanceAfter").asLong()).isEqualTo(6);
        JsonNode listed = body(mvc.perform(get("/clearing/v1/transfers").param("settlementRef", settlement).header("X-Clearing-Key", CC))
                .andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)).path("transfers");
        assertThat(listed.size()).isEqualTo(2);
    }

    @Test
    void aClientCanNeverDeliverSharesTheyDontHave() throws Exception {
        transfer("payout:" + UUID.randomUUID(), "PAY_OUT", bo, "INKWELL", 3);
        transfer("payin:" + UUID.randomUUID(), "PAY_IN", bo, "INKWELL", 5).andExpect(status().isUnprocessableEntity()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SECURITIES"));
        assertThat(held(bo, "INKWELL")).isEqualTo(3);
    }

    @Test
    void onlyTheClearingCorporationMovesSharesAndOnlyForRealAccounts() throws Exception {
        mvc.perform(post("/clearing/v1/transfers").header("X-Clearing-Key", DP).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("instructionId", "x", "kind", "PAY_OUT", "boId", bo, "symbol", "HARBOR",
                                "quantity", 1, "settlementRef", settlement))))
                .andExpect(status().isUnauthorized()).andExpect(MATCHES_CONTRACT);
        transfer("payout:" + UUID.randomUUID(), "PAY_OUT", "1208160099999999", "HARBOR", 1).andExpect(status().isNotFound());
        transfer("payout:" + UUID.randomUUID(), "PAY_OUT", "1200000000000001", "HARBOR", 1).andExpect(status().isNotFound());
        transfer("payout:" + UUID.randomUUID(), "PAY_OUT", bo, "HARBOR", 0).andExpect(status().isBadRequest());
    }

    @Test
    void racingPayInsCanNeverTakeTheSameSharesTwice() throws Exception {
        transfer("payout:" + UUID.randomUUID(), "PAY_OUT", bo, "KOSHA", 5);
        AtomicInteger done = new AtomicInteger();
        List<Thread> racers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            racers.add(Thread.ofVirtual().start(() -> {
                try {
                    if (transfer("payin:" + UUID.randomUUID(), "PAY_IN", bo, "KOSHA", 2).andReturn().getResponse().getStatus() == 201) {
                        done.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // counted as not done
                }
            }));
        }
        for (Thread t : racers) {
            t.join();
        }
        assertThat(done.get()).isEqualTo(2);
        assertThat(held(bo, "KOSHA")).isEqualTo(1);
    }
}
