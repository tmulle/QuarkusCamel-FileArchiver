package com.acme;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class Response {
    private String message;
    private Integer code;
}
