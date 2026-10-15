package com.example.yano.indexer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class YanoIndexerApplication {

    public static void main(String[] args) {
        SpringApplication.run(YanoIndexerApplication.class, args);
    }
}
