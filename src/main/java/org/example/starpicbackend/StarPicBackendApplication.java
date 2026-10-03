package org.example.starpicbackend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@MapperScan("org.example.starpicbackend.mapper")
@EnableAspectJAutoProxy(exposeProxy = true)
public class StarPicBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarPicBackendApplication.class, args);
    }

}
