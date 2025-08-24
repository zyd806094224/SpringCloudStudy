package com.example.model.common;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class R {
    private Integer code;
    private String message;
    private Object data;

    public static R ok() {
        R r = new R();
        r.setCode(200);
        return r;
    }

    public static R ok(String message, Object data) {
        R r = new R();
        r.setCode(200);
        r.setMessage(message);
        r.setData(data);
        return r;
    }

    public static R error() {
        R r = new R();
        r.setCode(500);
        return r;
    }

    public static R error(Integer code, String message) {
        R r = new R();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }
}
