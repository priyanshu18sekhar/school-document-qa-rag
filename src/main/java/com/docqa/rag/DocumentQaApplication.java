package com.docqa.rag;

import com.docqa.rag.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RagProperties.class)
public class DocumentQaApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentQaApplication.class, args);
    }
}
