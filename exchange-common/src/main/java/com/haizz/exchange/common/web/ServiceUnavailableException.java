package com.haizz.exchange.common.web;

public class ServiceUnavailableException extends BaseException {

    public ServiceUnavailableException(String errorCode, String message) {
        super(errorCode, message);
    }

    @Override
    public int getHttpStatus() {
        return 503;
    }
}
