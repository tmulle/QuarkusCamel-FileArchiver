package com.acme;

import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;

import javax.inject.Inject;

public class CamelRouteTest extends RouteBuilder {


    @Inject
    Endpoint directToJMS;

    @Override
    public void configure() throws Exception {

//        restConfiguration()
//                .contextPath("/api")
//                .bindingMode(RestBindingMode.json)
//                .skipBindingOnErrorCode(true)
//                .enableCORS(true)
//                .dataFormatProperty("prettyPrint", "true");
//
//
//        rest().post("/json")
//                .routeId("rest-with-json")
//                .consumes("application/json")
//                .type(Payload.class)
//                .to("direct:pojoToJms")
//                .post("/incoming").routeId("rest-id")
//                .to("direct:sender");
//
//
//
//        // When passing a POJO to another route, we have to remarshal it
//        // because the direct routes use inputstreams
//        from("direct:pojoToJms")
////                .wireTap("direct:tapper")
//                .log("Received Body: ${body}")
//                .marshal().json().to("direct:sender");
//
//
//
//        from("direct:sender").routeId("direct-sender")
//                .throttle(3).rejectExecution(true)
//                .log("Sending ${body} to Artemis")
//                .to("jms:queue:QueueIN")
//                .log("Sent message to Artemis")
//                .process(exchange -> exchange.getIn()
//                        .setBody("Success!"))
//                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(204));
//
//
//        from("timer:mytimer?period=5000").routeId("generate-route")
//                .transform(constant("HELLO from Camel!"))
//                .to("direct:sender")
//                .end();
    }


}
