package com.zhiliang.airag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiRagApplication.class, args);
    }
}
