package cn.hnust.selection.security;

import cn.hnust.selection.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 将已认证请求的访问拒绝转换为统一 Result JSON。
 * 包括 URL/方法权限不足、管理员能力不足，以及 CSRF 令牌缺失或不匹配；对客户端统一返回 403。
 */
public class JsonAccessDeniedHandler implements AccessDeniedHandler {
    private final ObjectMapper objectMapper;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        // 不回传框架异常详情，避免向前端暴露安全配置或内部资源信息。
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
            Result.failure("FORBIDDEN", "当前账号无权执行此操作", null));
    }
}
