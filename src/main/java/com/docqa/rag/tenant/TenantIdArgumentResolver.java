package com.docqa.rag.tenant;

import com.docqa.rag.config.RagProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Binds a {@link TenantId} controller parameter from the tenant header.
 *
 * <p>Doing this as an argument resolver rather than {@code @RequestHeader String}
 * means the validation (present, non-blank, sane length, sane charset) happens
 * in exactly one place and cannot be forgotten on a new endpoint. Declaring
 * {@code TenantId} in a handler signature is the only way to obtain one, and
 * obtaining one always runs this check.
 */
@Component
public class TenantIdArgumentResolver implements HandlerMethodArgumentResolver {

    private final RagProperties.Tenant config;

    public TenantIdArgumentResolver(RagProperties properties) {
        this.config = properties.tenant();
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return TenantId.class.equals(parameter.getParameterType());
    }

    @Override
    public TenantId resolveArgument(MethodParameter parameter,
                                    @Nullable ModelAndViewContainer mavContainer,
                                    NativeWebRequest webRequest,
                                    @Nullable WebDataBinderFactory binderFactory) {
        String raw = webRequest.getHeader(config.header());
        return resolve(raw);
    }

    /** Package-visible so the filter and the tests can use the same rules. */
    public TenantId resolve(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            throw new MissingTenantException(
                    "Missing required header '%s'. Every request must identify its tenant."
                            .formatted(config.header()));
        }
        String trimmed = raw.trim();
        if (trimmed.length() > config.maxLength()) {
            throw new MissingTenantException(
                    "Header '%s' exceeds the maximum length of %d characters."
                            .formatted(config.header(), config.maxLength()));
        }
        // Tenant ids end up in log lines and metric tags. Restricting the
        // charset keeps log injection (a newline in a header) and unbounded
        // metric cardinality out of the picture. It is not what enforces
        // isolation - parameterised SQL does that - it is hygiene.
        if (!isSafe(trimmed)) {
            throw new MissingTenantException(
                    "Header '%s' may only contain letters, digits, '-', '_' and '.'."
                            .formatted(config.header()));
        }
        return TenantId.of(trimmed);
    }

    private static boolean isSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
