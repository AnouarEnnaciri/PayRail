
package com.payrail.backend.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.security.Principal;

@Configuration
public class TransferAuditConfig implements WebMvcConfigurer {

    private static final Logger log =
            LoggerFactory.getLogger(TransferAuditConfig.class);

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        registry.addInterceptor(new HandlerInterceptor() {

            @Override
            public void afterCompletion(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    Object handler,
                    Exception ex
            ) {
                if (!"POST".equals(request.getMethod())) {
                    return;
                }

                Principal principal = request.getUserPrincipal();

                String actor = principal != null
                        ? principal.getName()
                        : "anonymous";

                log.info(
                        "AUDIT action=CLIENT_WRITE actor={} path={} status={}",
                        actor,
                        request.getRequestURI(),
                        response.getStatus()
                );
            }

        }).addPathPatterns("/api/client/**");
    }
}
