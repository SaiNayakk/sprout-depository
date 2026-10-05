package app.sprout.depository.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.depository} in depository.yml. */
@ConfigurationProperties("sprout.depository")
public record DepositoryProperties(List<Participant> participants, String clearingKey, String settlementAccount) {

    /** A depository participant (a broker): its name, 8-digit DP id and API key. */
    public record Participant(String name, String dpId, String key) {}
}
