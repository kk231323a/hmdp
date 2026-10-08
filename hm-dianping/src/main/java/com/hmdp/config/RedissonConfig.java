package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    @Value("${spring.redis.host}")
    private String host;
    @Value("${spring.redis.port}")
    private String redisPort;
    @Value("${spring.redis.password}")
    private String password;
    @Bean
    public RedissonClient redissonClient() {
//    创建配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://"+host+":"+redisPort).setPassword(password);
//    创建RedissonClient对象

        return Redisson.create(config);
    }

}
