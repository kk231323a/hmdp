package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {


    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        //基于token获取redis中的用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
        //2.判断用户是否存在
        if (userMap.isEmpty()) {
            //3.不存在，拦截
            return true;
        }

        //4.将查询到的Hash数据转为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //5.存在，保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);
        //6.刷新token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, java.util.concurrent.TimeUnit.MINUTES);
        return HandlerInterceptor.super.preHandle(request, response, handler);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();

        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
