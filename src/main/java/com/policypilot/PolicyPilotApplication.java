package com.policypilot;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PolicyPilotApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(PolicyPilotApplication.class);
        // "ingest" is a one-shot command: run it without starting the web server, then exit.
        if (Arrays.asList(args).contains("ingest")) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        app.run(args);
    }
}
