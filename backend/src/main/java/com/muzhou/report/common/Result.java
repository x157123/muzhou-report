package com.muzhou.report.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体，见 docs/CONTRACT.md §1。
 */
@Data
public class Result<T> implements Serializable {

    public static final int CODE_OK = 0;
    public static final int CODE_FAIL = 500;
    public static final int CODE_BAD_REQUEST = 400;

    private int code = CODE_OK;
    private String msg = "ok";
    private T data;

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(CODE_OK);
        r.setMsg("ok");
        r.setData(data);
        return r;
    }

    public static <T> Result<T> fail(String msg) {
        return fail(CODE_FAIL, msg);
    }

    public static <T> Result<T> fail(int code, String msg) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMsg(msg);
        return r;
    }

    public boolean isSuccess() {
        return code == CODE_OK;
    }
}
