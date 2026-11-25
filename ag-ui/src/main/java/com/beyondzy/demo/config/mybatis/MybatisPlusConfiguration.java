package com.beyondzy.demo.config.mybatis;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 扫描 mapper 根包及其子包。 */
@Configuration
@MapperScan("com.beyondzy.demo.mapper")
public class MybatisPlusConfiguration {

    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        return MybatisPages.interceptor();
    }
}
