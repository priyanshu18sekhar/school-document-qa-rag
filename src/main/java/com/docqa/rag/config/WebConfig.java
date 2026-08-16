package com.docqa.rag.config;

import com.docqa.rag.tenant.TenantIdArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TenantIdArgumentResolver tenantIdArgumentResolver;

    public WebConfig(TenantIdArgumentResolver tenantIdArgumentResolver) {
        this.tenantIdArgumentResolver = tenantIdArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(tenantIdArgumentResolver);
    }
}
