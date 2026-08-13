package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    StringRedisTemplate stringRedisTemplate;
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
                ).order(1);
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);//拦截所有请求
        WebMvcConfigurer.super.addInterceptors(registry);
    }
}
