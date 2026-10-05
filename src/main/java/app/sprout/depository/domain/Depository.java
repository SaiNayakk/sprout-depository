package app.sprout.depository.domain;

import app.sprout.depository.config.DepositoryProperties;
import app.sprout.depository.config.DepositoryProperties.Participant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Demat accounts and transfers. A transfer is one transaction: record the instruction (its id is
 * unique, so it can only happen once), lock the holding, refuse it whole if a client would go below
 * zero, move the shares, and write each account's movement.
 */
@Service
public class Depository {

    public enum Kind { PAY_IN, PAY_OUT }

    public record Account(String boId, String participant, String clientRef, String holderName, Instant openedAt) {}

    public record Holding(String symbol, long quantity) {}

    public record ClientHolding(String boId, String clientRef, String symbol, long quantity) {}

    public record Movement(String symbol, long quantity, long balanceAfter, String kind, String instructionId, String settlementRef,
                           Instant at) {}

    public record TransferRequest(String instructionId, Kind kind, String boId, String symbol, long quantity, String settlementRef) {}

    public record Transfer(String instructionId, Kind kind, String boId, String symbol, long quantity, String settlementRef,
                           Instant executedAt) {}

    public record Done<T>(T value, boolean created) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DepositoryProperties props;

    public Depository(JdbcClient db, TransactionTemplate tx, Clock clock, DepositoryProperties props) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
    }

    public void ensureSettlementAccount() {
        db.sql("INSERT INTO accounts (bo_id, holder_name, settlement, opened_at) VALUES (?, 'Sprout Clearing Corporation (settlement)', true, ?) "
                + "ON CONFLICT DO NOTHING").params(props.settlementAccount(), ts(clock.instant())).update();
    }

    // ── callers ──────────────────────────────────────────────────────────────

    public Participant participant(String key) {
        if (key != null) {
            for (Participant p : props.participants()) {
                if (MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), p.key().getBytes(StandardCharsets.UTF_8))) {
                    return p;
                }
            }
        }
        throw new ApiException(ErrorCode.UNAUTHENTICATED, "Send a valid X-Participant-Key.");
    }

    public void requireClearing(String key) {
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), props.clearingKey().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Only the clearing corporation can move shares.");
        }
    }

    // ── accounts ─────────────────────────────────────────────────────────────

    public Done<Account> open(Participant p, String clientRef, String holderName) {
        if (clientRef == null || !clientRef.matches("[A-Za-z0-9-]{1,64}")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "clientRef is 1 to 64 letters, digits or dashes.");
        }
        if (holderName == null || holderName.isBlank() || holderName.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "holderName is 2 to 100 characters.");
        }
        Optional<Account> existing = byClient(p, clientRef);
        if (existing.isPresent()) {
            return new Done<>(existing.get(), false);
        }
        long number = db.sql("SELECT nextval('client_numbers')").query(Long.class).single();
        String boId = p.dpId() + String.format("%08d", number);
        try {
            db.sql("INSERT INTO accounts (bo_id, participant, client_ref, holder_name, opened_at) VALUES (?, ?, ?, ?, ?)")
                    .params(boId, p.name(), clientRef, holderName.trim(), ts(clock.instant())).update();
        } catch (DuplicateKeyException e) {
            return new Done<>(byClient(p, clientRef).orElseThrow(() -> e), false);
        }
        return new Done<>(account(boId).orElseThrow(), true);
    }

    /** A participant sees only its own clients' accounts. */
    public Account mine(Participant p, String boId) {
        return account(boId).filter(a -> p.name().equals(a.participant()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "None of your clients has the demat account " + boId + "."));
    }

    public List<Holding> holdings(String boId) {
        return db.sql("SELECT symbol, quantity FROM holdings WHERE bo_id = ? AND quantity <> 0 ORDER BY symbol").param(boId)
                .query((rs, n) -> new Holding(rs.getString(1), rs.getLong(2))).list();
    }

    /** Every holding of all a participant's clients, to reconcile the participant's books with the depository. */
    public List<ClientHolding> allHoldings(Participant p) {
        return db.sql("""
                        SELECT a.bo_id, a.client_ref, h.symbol, h.quantity FROM holdings h JOIN accounts a ON a.bo_id = h.bo_id
                        WHERE a.participant = ? AND h.quantity <> 0 ORDER BY a.bo_id, h.symbol""")
                .param(p.name()).query((rs, n) -> new ClientHolding(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4))).list();
    }

    public List<Movement> movements(String boId) {
        return db.sql("""
                        SELECT m.symbol, m.quantity, m.balance_after, m.kind, m.instruction_id, t.settlement_ref, m.at
                        FROM movements m JOIN transfers t ON t.instruction_id = m.instruction_id
                        WHERE m.bo_id = ? ORDER BY m.id DESC LIMIT 100""")
                .param(boId)
                .query((rs, n) -> new Movement(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getTimestamp(7).toInstant()))
                .list();
    }

    // ── transfers ────────────────────────────────────────────────────────────

    public Done<Transfer> transfer(TransferRequest r) {
        validate(r);
        String hash = hash(r);
        Optional<Done<Transfer>> earlier = existing(r.instructionId(), hash);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        Account client = account(r.boId()).filter(a -> a.participant() != null)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No client demat account " + r.boId() + "."));
        String from = r.kind() == Kind.PAY_IN ? client.boId() : props.settlementAccount();
        String to = r.kind() == Kind.PAY_IN ? props.settlementAccount() : client.boId();
        try {
            return tx.execute(s -> {
                Instant now = clock.instant();
                db.sql("""
                                INSERT INTO transfers (instruction_id, request_hash, kind, bo_id, symbol, quantity, settlement_ref, executed_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                        .params(r.instructionId(), hash, r.kind().name(), r.boId(), r.symbol(), r.quantity(), r.settlementRef(), ts(now))
                        .update();
                // lock both holdings in a fixed order, so two transfers can never deadlock
                for (String bo : from.compareTo(to) < 0 ? List.of(from, to) : List.of(to, from)) {
                    db.sql("INSERT INTO holdings (bo_id, symbol, quantity) VALUES (?, ?, 0) ON CONFLICT DO NOTHING").params(bo, r.symbol()).update();
                    db.sql("SELECT quantity FROM holdings WHERE bo_id = ? AND symbol = ? FOR UPDATE").params(bo, r.symbol())
                            .query(Long.class).single();
                }
                long fromAfter = move(from, r.symbol(), -r.quantity());
                if (fromAfter < 0 && !from.equals(props.settlementAccount())) {
                    throw new ApiException(ErrorCode.INSUFFICIENT_SECURITIES, "Demat account " + from + " holds " + (fromAfter + r.quantity())
                            + " " + r.symbol() + ", not " + r.quantity() + ". Nothing moved.");
                }
                long toAfter = move(to, r.symbol(), r.quantity());
                record(from, r.symbol(), -r.quantity(), fromAfter, r, now);
                record(to, r.symbol(), r.quantity(), toAfter, r, now);
                return new Done<>(new Transfer(r.instructionId(), r.kind(), r.boId(), r.symbol(), r.quantity(), r.settlementRef(), now), true);
            });
        } catch (DuplicateKeyException e) {
            // the same instruction raced in and won
            return existing(r.instructionId(), hash).orElseThrow(() -> e);
        }
    }

    public List<Transfer> transfers(String settlementRef) {
        return db.sql(TRANSFER_SQL + " WHERE settlement_ref = ? ORDER BY executed_at, instruction_id").param(settlementRef)
                .query(Depository::transfer).list();
    }

    private void validate(TransferRequest r) {
        if (r.instructionId() == null || r.instructionId().isBlank() || r.instructionId().length() > 120) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "instructionId is 1 to 120 characters.");
        }
        if (r.kind() == null || r.boId() == null || r.symbol() == null || r.symbol().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "kind, boId and symbol are required.");
        }
        if (r.quantity() < 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "quantity is at least 1.");
        }
        if (r.settlementRef() == null || r.settlementRef().isBlank() || r.settlementRef().length() > 120) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "settlementRef is 1 to 120 characters.");
        }
    }

    private long move(String boId, String symbol, long delta) {
        return db.sql("UPDATE holdings SET quantity = quantity + ? WHERE bo_id = ? AND symbol = ? RETURNING quantity")
                .params(delta, boId, symbol).query(Long.class).single();
    }

    private void record(String boId, String symbol, long quantity, long after, TransferRequest r, Instant at) {
        db.sql("INSERT INTO movements (bo_id, symbol, quantity, balance_after, kind, instruction_id, at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(boId, symbol, quantity, after, r.kind().name(), r.instructionId(), ts(at)).update();
    }

    private Optional<Done<Transfer>> existing(String instructionId, String hash) {
        return db.sql("SELECT request_hash FROM transfers WHERE instruction_id = ?").param(instructionId).query(String.class).optional()
                .map(h -> {
                    if (!h.equals(hash)) {
                        throw new ApiException(ErrorCode.INSTRUCTION_CONFLICT, "A different transfer already has the instruction id " + instructionId + ".");
                    }
                    return new Done<>(db.sql(TRANSFER_SQL + " WHERE instruction_id = ?").param(instructionId).query(Depository::transfer).single(), false);
                });
    }

    private Optional<Account> account(String boId) {
        return db.sql(ACCOUNT_SQL + " WHERE bo_id = ?").param(boId).query(Depository::account).optional();
    }

    private Optional<Account> byClient(Participant p, String clientRef) {
        return db.sql(ACCOUNT_SQL + " WHERE participant = ? AND client_ref = ?").params(p.name(), clientRef).query(Depository::account).optional();
    }

    private static final String ACCOUNT_SQL = "SELECT bo_id, participant, client_ref, holder_name, opened_at FROM accounts";
    private static final String TRANSFER_SQL = "SELECT instruction_id, kind, bo_id, symbol, quantity, settlement_ref, executed_at FROM transfers";

    private static Account account(ResultSet rs, int n) throws SQLException {
        return new Account(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant());
    }

    private static Transfer transfer(ResultSet rs, int n) throws SQLException {
        return new Transfer(rs.getString(1), Kind.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getLong(5),
                rs.getString(6), rs.getTimestamp(7).toInstant());
    }

    static String hash(TransferRequest r) {
        String canonical = String.join("|", r.kind().name(), r.boId(), r.symbol(), String.valueOf(r.quantity()), r.settlementRef());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
