package com.haizz.exchange.common.web;

import java.util.Map;

public class ValidationException extends BaseException {

    private final Map<String, String> fieldErrors;

    public ValidationException(String errorCode, String message) {
        super(errorCode, message);
        this.fieldErrors = null;
    }

    public ValidationException(String errorCode, String message, Map<String, String> fieldErrors) {
        super(errorCode, message);
        this.fieldErrors = fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    @Override
    public int getHttpStatus() {
        return 400;
    }
}
