package cn.hnust.selection.security;

import cn.hnust.selection.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 将安全过滤器层的未认证异常转换为统一 Result JSON。
 * 此处理发生在 Controller 执行前，因此专门覆盖“请求没有有效登录身份”的 401 响应。
 */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        // 不把底层认证异常或账号是否存在等内部信息回传给客户端。
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
            Result.failure("UNAUTHENTICATED", "请先登录", null));
    }
}
