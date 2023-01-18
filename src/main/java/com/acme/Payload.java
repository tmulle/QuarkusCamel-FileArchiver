package com.acme;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Getter
@NoArgsConstructor
public class Payload {

    private Integer id;
    private String firstName;
}
