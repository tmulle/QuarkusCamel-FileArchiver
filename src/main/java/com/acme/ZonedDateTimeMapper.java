package com.acme;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.jackson.ObjectMapperCustomizer;

import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Produces;
import javax.inject.Singleton;

@Singleton
public class ZonedDateTimeMapper {


//    @Produces
//    ObjectMapper objectMapper(Instance<ObjectMapperCustomizer> customizers) {
//        // Your own `ObjectMapper` or one provided by another library
//        ObjectMapper mapper = JsonMapper.builder()
//                .addModule(new JavaTimeModule())
//                .build();
//        // Apply customizations (includes customizations from Quarkus)
//        for (ObjectMapperCustomizer customizer : customizers) {
//            customizer.customize(mapper);
//        }
//        return mapper;
//    }
}
