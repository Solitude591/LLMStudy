package com.llmstudy.rag.auth.authorization;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.llmstudy.rag.dto.ApiResult;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 认证与授权异常的统一 HTTP 响应转换器。
 *
 * <p>保持 {@link ApiResult} 响应结构一致，同时使用真实 HTTP 状态码，方便页面和调用方
 * 区分“需要重新登录”“没有权限”和“认证基础设施不可用”。</p>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    /** 将未登录、Token 失效和账号认证失败统一转换为 401。 */
    @ExceptionHandler({NotLoginException.class, AuthenticationException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResult<Void> unauthorized(RuntimeException exception) {
        return ApiResult.fail(401, exception.getMessage());
    }

    /** 将角色、权限及资源级授权失败统一转换为 403。 */
    @ExceptionHandler({NotRoleException.class, NotPermissionException.class,
            ResourceAccessDeniedException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResult<Void> forbidden(RuntimeException exception) {
        return ApiResult.fail(403, exception.getMessage());
    }

    /**
     * Redis 无法连接时返回 503。
     *
     * <p>Sa-Token 登录态以 Redis 为事实来源，故障时不能退化为放行请求。</p>
     */
    @ExceptionHandler(RedisConnectionFailureException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResult<Void> redisUnavailable(RedisConnectionFailureException exception) {
        return ApiResult.fail(503, "认证服务暂时不可用");
    }
}
