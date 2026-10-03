package cn.hnust.selection.exception;

import cn.hnust.selection.common.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;

import javax.validation.ConstraintViolationException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 全局 HTTP 异常映射测试。
 *
 * <p>授权接口使用必填查询参数以及路径 ID 约束；这两类输入错误分别由 Spring MVC 和 Bean Validation
 * 抛出不同异常。本测试确认两者都落到同一个 400/INVALID_ARGUMENT 契约，避免参数问题被误报为 500。</p>
 */
class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void missingRequiredQueryParameterBecomesInvalidArgument() {
        ResponseEntity<Result<?>> response = handler.handleInvalidRequest(
            new MissingServletRequestParameterException("collegeId", "Long"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_ARGUMENT", response.getBody().getCode());
    }

    @Test
    void invalidControllerMethodConstraintBecomesInvalidArgument() {
        ResponseEntity<Result<?>> response = handler.handleInvalidRequest(
            new ConstraintViolationException("must be positive", Collections.emptySet()));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_ARGUMENT", response.getBody().getCode());
    }
}
