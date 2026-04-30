package com.haizz.exchange.common.web;

public class ForbiddenException extends BaseException {

    public ForbiddenException(String errorCode, String message) {
        super(errorCode, message);
    }

    @Override
    public int getHttpStatus() {
        return 403;
    }
}
