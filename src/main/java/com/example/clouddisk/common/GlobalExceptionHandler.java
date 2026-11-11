package com.example.clouddisk.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理：把异常转成统一返回结构
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegal(IllegalArgumentException e) {
        return Result.error(e.getMessage());
    }

    /** @Valid 校验失败：只回第一个字段的提示，不要把框架内部信息抛给前端 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleInvalid(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        return Result.error(fe != null && fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "请求参数不合法");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return Result.error("请求体格式不正确");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Result<Void> handleTooLarge(MaxUploadSizeExceededException e) {
        return Result.error("文件超过大小限制，请调整 application.yml 中的 max-file-size / max-request-size");
    }

    /**
     * 重复文件：这不是「上传失败」，而是「这份内容库里已经有了」。
     * 用 409 + code=2 单独区分，前端才能把它算成「跳过」而不是弹一堆红色错误。
     * data 里带上已存在文件的 id / 名字，方便提示「与 IMG_1234.jpg 相同」。
     */
    @ExceptionHandler(DuplicateFileException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Map<String, Object>> handleDuplicate(DuplicateFileException e) {
        Map<String, Object> data = new HashMap<>();
        data.put("duplicate", true);
        data.put("existingId", e.getExistingId());
        data.put("existingName", e.getExistingName());
        Result<Map<String, Object>> r = Result.error(2, e.getMessage());
        r.setData(data);
        return r;
    }

    /**
     * 兜底异常。
     *
     * 注意「响应已经提交」这种情况：视频 / 图片是边读边写的流式输出，Safari 拖动进度、
     * 切走页面、预加载取消时都会**主动断开**连接，此时 Tomcat 侧抛 IOException 会一路冒到这里。
     * 如果还按普通异常去写 JSON，Spring 会因为「Content-Type 已经是 video/quicktime」
     * 再抛一次 HttpMessageNotWritableException，日志里留下一大段看着像故障的堆栈
     * （`Failure in @ExceptionHandler`）。这类情况只记一行 debug，不再写响应体。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handle(Exception e, HttpServletResponse response) {
        if (response.isCommitted()) {
            log.debug("响应已提交（多半是客户端中断了流式输出），忽略异常: {}", e.toString());
            return null;
        }
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        log.warn("请求处理异常: {}", e.toString());
        return Result.error("服务异常：" + e.getMessage());
    }
}
