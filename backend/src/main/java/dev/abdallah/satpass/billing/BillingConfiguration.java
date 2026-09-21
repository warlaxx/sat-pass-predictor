package dev.abdallah.satpass.billing;

import com.zaxxer.hikari.HikariDataSource;
import dev.abdallah.satpass.access.AccountProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties(BillingProperties.class)
@ConditionalOnProperty(name = "billing.enabled", havingValue = "true")
public class BillingConfiguration {
    @Bean BillingGateway billingGateway(BillingProperties properties, AccountProperties accounts) {
        if (!accounts.enabled()) throw new IllegalArgumentException("Billing requires self-serve accounts");
        return new StripeGateway(properties, accounts.baseUrl());
    }
    @Bean BillingService billingService(HikariDataSource source, BillingGateway stripe, BillingProperties properties) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(30);
        return new BillingService(new JdbcTemplate(source), tx, stripe, properties);
    }
}
