package com.moiz.ledgerr.config.jpa;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.moiz.ledgerr",
        entityManagerFactoryRef = "ledgerEntityManagerFactory",
        transactionManagerRef = "ledgerTransactionManager"
)
public class LedgerDbConfig {

    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties ledgerDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource ledgerDataSource(
            @Qualifier("ledgerDataSourceProperties") DataSourceProperties properties
    ) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean ledgerEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("ledgerDataSource") DataSource dataSource,
            JpaProperties jpaProperties
    ) {
        return builder
                .dataSource(dataSource)
                .packages("com.moiz.ledgerr")
                .persistenceUnit("ledger")
                .properties(jpaProperties.getProperties())
                .build();
    }

    @Bean
    public PlatformTransactionManager ledgerTransactionManager(
            @Qualifier("ledgerEntityManagerFactory") EntityManagerFactory entityManagerFactory
    ) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}

