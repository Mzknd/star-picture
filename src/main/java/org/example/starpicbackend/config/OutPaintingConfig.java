package org.example.starpicbackend.config;

import org.example.starpicbackend.api.outpainting.AliyunOutPaintingClient;
import org.example.starpicbackend.api.outpainting.OutPaintingProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;

@Configuration
public class OutPaintingConfig {
    @Bean
    public OutPaintingProvider outPaintingProvider(OutPaintingProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        return new AliyunOutPaintingClient(properties, new RestTemplate(factory));
    }

    @Bean("outPaintingClock")
    public Clock outPaintingClock() {
        return Clock.systemUTC();
    }
}
