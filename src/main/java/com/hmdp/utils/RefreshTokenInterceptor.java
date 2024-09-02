package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * @author RainSoul
 * @create 2024-09-02
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 在处理请求之前进行处理
     * 主要用于全局统一的身份验证、日志记录等
     *
     * @param request  请求对象，用于获取请求信息
     * @param response 响应对象，用于发送响应信息
     * @param handler  处理器对象，用于处理请求
     * @return 返回true继续执行下一个拦截器或处理器，返回false终止请求处理
     * @throws Exception 可能抛出的异常
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取请求头中的token
        String token = request.getHeader("authorization");
        // 如果token为空或空白，继续执行下一个拦截器或处理器
        if (StrUtil.isBlank(token)) {
            return true;
        }
        // 根据token生成Redis中的用户信息键
        String key = RedisConstants.LOGIN_USER_KEY + token;
        // 从Redis中获取用户信息的Map
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // 如果用户信息Map为空，继续执行下一个拦截器
        if (userMap.isEmpty()) {
            return true;
        }

        // 将用户信息Map填充到UserDTO对象中
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 保存UserDTO对象到线程本地存储
        UserHolder.saveUser(userDTO);
        // 设置Redis中用户信息键的过期时间
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 继续执行下一个拦截器或处理器
        return true;
    }


    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
