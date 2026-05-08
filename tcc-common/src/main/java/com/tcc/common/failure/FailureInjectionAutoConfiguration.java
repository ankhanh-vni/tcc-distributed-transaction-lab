package com.tcc.common.failure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "tcc.failure-injection.enabled", havingValue = "true", matchIfMissing = true)
public class FailureInjectionAutoConfiguration {

    @Bean
    public FilterRegistrationBean<FailureInjectionFilter> failureInjectionFilter() {
        FilterRegistrationBean<FailureInjectionFilter> reg = new FilterRegistrationBean<>(new FailureInjectionFilter());
        reg.addUrlPatterns("/tcc/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }
}
