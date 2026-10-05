package app.sprout.depository.web;

import app.sprout.depository.config.DepositoryProperties.Participant;
import app.sprout.depository.domain.ApiException;
import app.sprout.depository.domain.Depository;
import app.sprout.depository.domain.Depository.Account;
import app.sprout.depository.domain.Depository.Done;
import app.sprout.depository.domain.Depository.Kind;
import app.sprout.depository.domain.Depository.Transfer;
import app.sprout.depository.domain.Depository.TransferRequest;
import app.sprout.depository.domain.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The depository's API (depository-v1.yaml): participants' demat accounts, and the clearing corporation's transfers. */
@RestController
public class DepositoryController {

    public record OpenRequest(String clientRef, String holderName) {}

    public record TransferBody(String instructionId, Kind kind, String boId, String symbol, Long quantity, String settlementRef) {}

    private final Depository depository;

    public DepositoryController(Depository depository) {
        this.depository = depository;
    }

    // ── participants ─────────────────────────────────────────────────────────

    @PostMapping("/participant/v1/accounts")
    public ResponseEntity<Map<String, Object>> open(@RequestHeader(value = "X-Participant-Key", required = false) String key,
                                                    @RequestBody OpenRequest req) {
        Done<Account> a = depository.open(depository.participant(key), req.clientRef(), req.holderName());
        return ResponseEntity.status(a.created() ? HttpStatus.CREATED : HttpStatus.OK).body(account(a.value()));
    }

    @GetMapping("/participant/v1/accounts/{boId}")
    public Map<String, Object> get(@RequestHeader(value = "X-Participant-Key", required = false) String key, @PathVariable String boId) {
        return account(depository.mine(depository.participant(key), boId));
    }

    @GetMapping("/participant/v1/accounts/{boId}/holdings")
    public Map<String, Object> holdings(@RequestHeader(value = "X-Participant-Key", required = false) String key,
                                        @PathVariable String boId) {
        Participant p = depository.participant(key);
        depository.mine(p, boId);
        return Map.of("boId", boId, "holdings", depository.holdings(boId).stream()
                .map(h -> Map.<String, Object>of("symbol", h.symbol(), "quantity", h.quantity())).toList());
    }

    @GetMapping("/participant/v1/accounts/{boId}/transactions")
    public Map<String, Object> transactions(@RequestHeader(value = "X-Participant-Key", required = false) String key,
                                            @PathVariable String boId) {
        depository.mine(depository.participant(key), boId);
        return Map.of("transactions", depository.movements(boId).stream().map(m -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", m.symbol());
            out.put("quantity", m.quantity());
            out.put("balanceAfter", m.balanceAfter());
            out.put("kind", m.kind());
            out.put("instructionId", m.instructionId());
            out.put("settlementRef", m.settlementRef());
            out.put("at", m.at().toString());
            return out;
        }).toList());
    }

    // ── the clearing corporation ─────────────────────────────────────────────

    @PostMapping("/clearing/v1/transfers")
    public ResponseEntity<Map<String, Object>> transfer(@RequestHeader(value = "X-Clearing-Key", required = false) String key,
                                                        @RequestBody TransferBody body) {
        depository.requireClearing(key);
        if (body.quantity() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "quantity is required.");
        }
        Done<Transfer> t = depository.transfer(new TransferRequest(body.instructionId(), body.kind(), body.boId(), body.symbol(),
                body.quantity(), body.settlementRef()));
        return ResponseEntity.status(t.created() ? HttpStatus.CREATED : HttpStatus.OK).body(transfer(t.value()));
    }

    @GetMapping("/clearing/v1/transfers")
    public Map<String, Object> transfers(@RequestHeader(value = "X-Clearing-Key", required = false) String key,
                                         @RequestParam String settlementRef) {
        depository.requireClearing(key);
        return Map.of("transfers", depository.transfers(settlementRef).stream().map(DepositoryController::transfer).toList());
    }

    static Map<String, Object> account(Account a) {
        return Map.of("boId", a.boId(), "clientRef", a.clientRef(), "holderName", a.holderName(), "openedAt", a.openedAt().toString());
    }

    static Map<String, Object> transfer(Transfer t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instructionId", t.instructionId());
        m.put("kind", t.kind().name());
        m.put("boId", t.boId());
        m.put("symbol", t.symbol());
        m.put("quantity", t.quantity());
        m.put("settlementRef", t.settlementRef());
        m.put("executedAt", t.executedAt().toString());
        return m;
    }
}
