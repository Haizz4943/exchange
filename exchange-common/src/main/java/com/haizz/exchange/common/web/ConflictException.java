package com.haizz.exchange.common.web;

public class ConflictException extends BaseException {

    public ConflictException(String errorCode, String message) {
        super(errorCode, message);
    }

    @Override
    public int getHttpStatus() {
        return 409;
    }
}
