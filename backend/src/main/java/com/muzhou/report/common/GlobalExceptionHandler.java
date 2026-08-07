package com.muzhou.report.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValid(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return Result.fail(Result.CODE_BAD_REQUEST, msg);
    }

    /**
     * 唯一索引撞车。
     *
     * <p>最常见的成因是**逻辑删除与唯一索引不配套**：`uk_report_code` / `uk_ds_code` /
     * `uk_dataset_code_scope` 都不带 `deleted` 列，而各处的 `checkCodeUnique` 只数未删除的行 ——
     * 于是「删掉一张报表，再用同一个 code 建一张」能通过校验、在 insert 时才撞上。
     * 不翻译的话用户看到的是「系统异常: Unique index or primary key violation: ...」加一整条
     * SQL，既看不懂也不知道该改什么。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一索引冲突: {}", e.getMessage());
        return Result.fail("编码已被占用（也可能是一条已删除的记录仍占着它），请换一个");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        return Result.fail(Result.CODE_BAD_REQUEST, e.getMessage());
    }

    /**
     * 兜底：**不把异常原文回给调用方**，只给一个编号，详情进日志。
     *
     * <p>以前是 `"系统异常: " + e.getMessage()` 原样回显。能说人话的错误本来就都包成了
     * {@link BizException}（SQL 报错在 {@code JdbcExecutor} 里就包了，接口取数失败在
     * {@code DatasetServiceImpl} 里包了），落到这里的都是**没预料到**的异常 ——
     * 它们的 message 往往带着表结构、内网地址、文件路径，对用户毫无用处，
     * 而本项目不做鉴权（见 docs/OPTIMIZATION.md §3），这些信息等于对所有人公开。
     *
     * <p>编号是给运维用的：用户报「系统异常，编号 a1b2c3d4」，日志里 grep 这个编号
     * 就能找到那条完整堆栈，比让用户复述半句英文报错可靠得多。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        log.error("系统异常 [{}]", traceId, e);
        return Result.fail("系统异常，请联系管理员（编号 " + traceId + "）");
    }
}
