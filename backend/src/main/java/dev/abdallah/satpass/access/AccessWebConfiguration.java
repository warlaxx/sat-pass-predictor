package dev.abdallah.satpass.access;

import dev.abdallah.satpass.api.PassController;
import dev.abdallah.satpass.api.VersionedPassController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AccessProperties.class)
public class AccessWebConfiguration implements WebMvcConfigurer {
    private final ObjectProvider<AccessService> access;
    private final AccessProperties properties;

    public AccessWebConfiguration(ObjectProvider<AccessService> access, AccessProperties properties) {
        this.access = access;
        this.properties = properties;
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                // Protect the resolved controller, not a hand-maintained path matcher.
                // This includes all aliases and cannot be bypassed with matrix parameters.
                if (!(handler instanceof HandlerMethod method)
                        || !PassController.class.isAssignableFrom(method.getBeanType())) return true;
                boolean demo = !VersionedPassController.class.isAssignableFrom(method.getBeanType());
                String key = request.getHeader("X-API-Key");
                // Keyed/quota-limited responses must never be served from a shared HTTP cache.
                response.setHeader("Cache-Control", "no-store");
                AccessService service = access.getIfAvailable();
                if (service == null) {
                    if (!demo || key != null) throw AccessFailure.unavailable();
                    return true; // Explicit legacy mode while PostgreSQL is not provisioned.
                }
                try {
                    service.admit(key, demo);
                } catch (DataAccessException | TransactionException failure) {
                    throw AccessFailure.unavailable(); // Fail closed; no secrets/SQL in the response.
                }
                return true;
            }
        });
    }

    @Override public void addCorsMappings(CorsRegistry registry) {
        if (!properties.allowedOrigins().isEmpty()) {
            registry.addMapping("/v1/**").allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                    .allowedMethods("GET").allowedHeaders("X-API-Key", "Content-Type")
                    .exposedHeaders("Retry-After").allowCredentials(false).maxAge(600);
        }
    }
}
