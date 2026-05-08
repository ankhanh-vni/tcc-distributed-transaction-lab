package com.tcc.common.dto;

import com.tcc.common.tcc.TccErrorCode;

public record TccErrorResponse(TccErrorCode code, String message) {
}
