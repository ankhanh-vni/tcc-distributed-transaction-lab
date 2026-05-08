package com.tcc.common.failure;

public enum FailAt {
    TRY,
    CONFIRM,
    CANCEL;

    public static FailAt fromHttpMethod(String method) {
        return switch (method) {
            case "POST" -> TRY;
            case "PUT" -> CONFIRM;
            case "DELETE" -> CANCEL;
            default -> null;
        };
    }
}
