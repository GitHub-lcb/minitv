package us.minitv.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import us.minitv.account.mapper.UserMapper;
import us.minitv.account.row.UserRow;
import us.minitv.common.ApiException;
import us.minitv.ops.mapper.SupplierMapper;
import us.minitv.ops.row.AdminApiKeyRow;
import us.minitv.ops.row.AdminUserRow;
import us.minitv.ops.row.SupplierRow;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request authentication argument resolvers, replacing FastAPI's Depends().
 *
 * Three audiences exist: end users (optional or required), platform staff and
 * suppliers. Staff/supplier routes accept either a Redis session token or an
 * {@code mk_live_} API key; the key is stamped on the request so the
 * api_key_events filter can record writes after the response.
 */
public final class AuthArgumentResolvers {

    public static final String ATTR_KEY_ID = "minitv.apiKeyId";
    public static final String ATTR_KEY_STAFF_ID = "minitv.apiKeyStaffId";

    public static final Set<String> STAFF_ROLES = Set.of("ops", "finance", "admin");
    public static final List<String> ROLES = List.of("ops", "finance", "admin", "partner");

    private AuthArgumentResolvers() {
    }

    static String bearer(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        return auth.substring("Bearer ".length());
    }

    @Component
    public static class UserResolver implements HandlerMethodArgumentResolver {

        private final SessionService sessions;
        private final UserMapper users;

        public UserResolver(SessionService sessions, UserMapper users) {
            this.sessions = sessions;
            this.users = users;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(CurrentUser.class)
                    && UserRow.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            CurrentUser annotation = parameter.getParameterAnnotation(CurrentUser.class);
            HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
            String token = request == null ? null : bearer(request);
            if (token == null || token.isEmpty()) {
                if (annotation != null && annotation.required()) {
                    throw ApiException.of(401, "login required");
                }
                return null;
            }
            Long userId = sessions.resolveUserId(token);
            UserRow user = userId == null ? null : users.findById(userId);
            if (user == null && annotation != null && annotation.required()) {
                throw ApiException.of(401, "login required");
            }
            return user;
        }
    }

    @Component
    public static class StaffResolver implements HandlerMethodArgumentResolver {

        private final SessionService sessions;
        private final ApiKeyService apiKeys;

        public StaffResolver(SessionService sessions, ApiKeyService apiKeys) {
            this.sessions = sessions;
            this.apiKeys = apiKeys;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(CurrentStaff.class)
                    && StaffContext.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
            String token = request == null ? null : bearer(request);
            if (token == null || token.isEmpty()) {
                throw new ApiException(401,
                        ApiException.detail("missing_credential", "缺少 Authorization 头"),
                        Map.of("WWW-Authenticate", "Bearer"));
            }
            AdminUserRow user;
            AdminApiKeyRow key = null;
            if (ApiKeyService.looksLikeKey(token)) {
                ApiKeyService.Resolved resolved = apiKeys.resolveCredential(token);
                user = resolved.user();
                key = resolved.key();
                request.setAttribute(ATTR_KEY_ID, key.getId());
                request.setAttribute(ATTR_KEY_STAFF_ID, user.getId());
                apiKeys.authorize(key, request.getMethod(), request.getRequestURI(), user.getRole());
            } else {
                user = sessions.resolveAdmin(token);
                if (user == null) {
                    throw new ApiException(401,
                            ApiException.detail("invalid_session", "会话无效或已过期"),
                            Map.of("WWW-Authenticate", "Bearer"));
                }
            }
            if (!STAFF_ROLES.contains(user.getRole())) {
                throw ApiException.forbidden("staff_only", "该接口仅对平台员工开放");
            }
            return new StaffContext(user, key, token);
        }
    }

    @Component
    public static class PartnerResolver implements HandlerMethodArgumentResolver {

        private final SessionService sessions;
        private final ApiKeyService apiKeys;
        private final SupplierMapper suppliers;

        public PartnerResolver(SessionService sessions, ApiKeyService apiKeys, SupplierMapper suppliers) {
            this.sessions = sessions;
            this.apiKeys = apiKeys;
            this.suppliers = suppliers;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(CurrentPartner.class)
                    && PartnerContext.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            CurrentPartner annotation = parameter.getParameterAnnotation(CurrentPartner.class);
            HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
            String token = request == null ? null : bearer(request);
            if (token == null || token.isEmpty()) {
                throw new ApiException(401,
                        ApiException.detail("missing_credential", "缺少 Authorization 头"),
                        Map.of("WWW-Authenticate", "Bearer"));
            }
            boolean keyCredential = ApiKeyService.looksLikeKey(token);
            if (keyCredential && annotation != null && !annotation.apiKeyAllowed()) {
                throw ApiException.forbidden("session_required", "该接口只能用登录会话调用");
            }
            AdminUserRow user;
            AdminApiKeyRow key = null;
            if (keyCredential) {
                ApiKeyService.Resolved resolved = apiKeys.resolveCredential(token);
                user = resolved.user();
                key = resolved.key();
                if ("partner".equals(user.getRole()) && !request.getRequestURI().startsWith("/partner/v1/")) {
                    throw ApiException.forbidden("partner_scope_denied", "上剧方密钥只能访问合作方接口");
                }
                request.setAttribute(ATTR_KEY_ID, key.getId());
                request.setAttribute(ATTR_KEY_STAFF_ID, user.getId());
            } else {
                user = sessions.resolveAdmin(token);
                if (user == null) {
                    throw new ApiException(401,
                            ApiException.detail("invalid_session", "会话无效或已过期"),
                            Map.of("WWW-Authenticate", "Bearer"));
                }
            }
            if (!"partner".equals(user.getRole()) || user.getSupplierId() == null) {
                throw ApiException.forbidden("partner_only", "该接口仅对上剧方开放");
            }
            SupplierRow supplier = suppliers.findById(user.getSupplierId());
            if (supplier == null || !"active".equals(supplier.getStatus())) {
                throw ApiException.forbidden("supplier_disabled", "供应商已停用");
            }
            if (key != null) {
                apiKeys.authorize(key, request.getMethod(), request.getRequestURI(), user.getRole());
            }
            return new PartnerContext(user, key, token, supplier);
        }
    }
}
