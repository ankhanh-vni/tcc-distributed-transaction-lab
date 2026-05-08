package com.tcc.common.tcc;

public class TccException extends RuntimeException {

    private final TccErrorCode code;

    public TccException(TccErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public TccErrorCode getCode() {
        return code;
    }
}
