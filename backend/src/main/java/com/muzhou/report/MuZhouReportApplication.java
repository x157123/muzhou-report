package com.muzhou.report;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 木舟报表(MuZhou Report) 启动类。
 */
@SpringBootApplication
@MapperScan("com.muzhou.report.mapper")
public class MuZhouReportApplication {

    public static void main(String[] args) {
        SpringApplication.run(MuZhouReportApplication.class, args);
    }
}
