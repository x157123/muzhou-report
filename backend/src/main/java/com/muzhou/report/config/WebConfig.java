package com.muzhou.report.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    public static final String DATE_TIME = "yyyy-MM-dd HH:mm:ss";
    public static final String DATE = "yyyy-MM-dd";

    /**
     * 跨域：放开来源，但**不允许携带凭据**。
     *
     * <p>本项目不做鉴权、没有会话（见 docs/OPTIMIZATION.md §3），
     * {@code allowCredentials(true)} 一点用都没有，却会让 Spring 把请求的 Origin 原样回写、
     * 并允许对方带上自己站点的 Cookie —— 将来谁在前面加了一层基于 Cookie 的网关，
     * 这一条会立刻把 CSRF 防线整个作废。关掉之后 {@code *} 才是真正意义上的"公开只读接口"。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }

    /**
     * 全局 Jackson：Java8 时间序列化、long 不转字符串、忽略未知字段。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(DATE_TIME);
        DateTimeFormatter df = DateTimeFormatter.ofPattern(DATE);
        // 注意：不能用 builder.modules(new JavaTimeModule()) 的方式注册这些序列化器 ——
        // Spring Boot 之后还会自动装配一个 JavaTimeModule，它会把同类型的序列化器覆盖掉，
        // 结果又变回 ISO 格式。serializerByType/deserializerByType 是在模块之后应用的，优先级更高。
        return builder -> {
            builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(dtf));
            builder.deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(dtf));
            builder.serializerByType(LocalDate.class, new LocalDateSerializer(df));
            builder.deserializerByType(LocalDate.class, new LocalDateDeserializer(df));
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        };
    }

    /**
     * 引擎内部使用的 ObjectMapper（报表 content 解析）。
     *
     * <p><b>注意</b>：Spring Boot 的 {@code JacksonAutoConfiguration#jacksonObjectMapper} 带有
     * {@code @ConditionalOnMissingBean(ObjectMapper.class)}——只要容器里出现<b>任何</b> ObjectMapper Bean，
     * 自动配置的那个就不会创建，MVC 的消息转换器会转而使用这里的实例。
     * 因此这个 Bean 必须是「配置完整」的那一个，绝不能 new 一个裸的 ObjectMapper，
     * 否则上面 jacksonCustomizer 里的日期格式会全部失效（接口会返回 ISO 格式时间）。
     *
     * <p>用 {@link Jackson2ObjectMapperBuilder} 构建即可自动套用全部 customizer，
     * 保证「Web 出参」与「引擎内部解析」用的是同一套规则。
     */
    @Bean("mzObjectMapper")
    @Primary
    public ObjectMapper mzObjectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper mapper = builder.createXmlMapper(false).build();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
