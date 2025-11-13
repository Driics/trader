package ru.driics.aitrade.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean

/**
 * Validation configuration for Spring Boot.
 *
 * Enables JSR-380 Bean Validation on controllers and services.
 */
@Configuration
class ValidationConfig {

    @Bean
    fun validator(): LocalValidatorFactoryBean = LocalValidatorFactoryBean()
}