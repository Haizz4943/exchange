package com.haizz.exchange.common.web;

public class NotFoundException extends BaseException {

    public NotFoundException(String errorCode, String message) {
        super(errorCode, message);
    }

    @Override
    public int getHttpStatus() {
        return 404;
    }
}
