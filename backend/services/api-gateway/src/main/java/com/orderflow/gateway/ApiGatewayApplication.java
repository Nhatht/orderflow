package com.orderflow.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** API Gateway — cửa vào duy nhất: định tuyến, xác thực JWT, giới hạn tần suất, gắn danh tính và correlationId. */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
