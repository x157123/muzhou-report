package com.muzhou.report.common;

/**
 * 业务异常，被 GlobalExceptionHandler 转成 Result.fail。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(Result.CODE_FAIL, message);
    }

    public BizException(String message, Throwable cause) {
        super(message, cause);
        this.code = Result.CODE_FAIL;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static BizException of(String format, Object... args) {
        return new BizException(String.format(format, args));
    }
}
