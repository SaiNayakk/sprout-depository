package app.sprout.depository.config;

import app.sprout.depository.domain.Depository;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DepositoryBeans {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ApplicationRunner settlementAccount(Depository depository) {
        return args -> depository.ensureSettlementAccount();
    }
}
