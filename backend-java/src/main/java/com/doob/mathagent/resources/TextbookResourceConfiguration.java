package com.doob.mathagent.resources;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class TextbookResourceConfiguration {

    /**
     * 创建教材资源配置：Spring 启动参数、测试属性和系统环境变量都可提供 processed_books 根目录。
     */
    @Bean
    TextbookResourceProperties textbookResourceProperties(Environment environment) {
        return TextbookResourceProperties.fromSpringEnvironment(environment);
    }
}
