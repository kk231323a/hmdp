package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())//添加拦截器
                .excludePathPatterns(//设置不拦截的路径
                        "/user/code",
                        "/user/login",
                        "/shop/**",
                        "/voucher/**",
                        "/upload/**",
                        "/blog/hot",
                        "/shop-type/**"
                );
        WebMvcConfigurer.super.addInterceptors(registry);
    }
}
