package com.beyondzy.demo.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 文档标题。接口文档默认关闭，打开后走登录校验。 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("海卓大脑").version("1.0"));
    }
}
