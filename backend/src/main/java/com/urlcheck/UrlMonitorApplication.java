package com.urlcheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.urlcheck.monitor.CheckProperties;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(CheckProperties.class)
public class UrlMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlMonitorApplication.class, args);
    }
}