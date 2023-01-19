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

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/*刷新redis中用户信息存在时间*/
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        /*获取到请求中带的token并加上前缀*/
        String token = LOGIN_USER_KEY+request.getHeader("authorization");

        /*如果拿到的token为空说明未登录，放行给第二个拦截器判断是否是需要登录的请求*/
        /*为啥不在这就拦截，在这拦截那么不需要登录的请求也被要求登录了（因为这个拦截器是拦截所有请求的，难道我不登录访问个普通网页，也给我拦截吗）*/
        if (StrUtil.isBlank(token)){
            return true;
        }

        /*通过token拿到redis中的用户数据*/
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(token);

        /*这不是判断entries是不是空,而是判断是它否是一个空map，里面是否有东西*/
        if (entries.isEmpty()) {
            return true;
        }

        /*到这还没放行说明map非空,则可以保存到ThreadLoad并刷新存在时间了*/
        UserDTO userDTO = BeanUtil.fillBeanWithMap(entries, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        stringRedisTemplate.expire(token,LOGIN_USER_TTL,TimeUnit.MINUTES);

        /*放行*/
        return true;
    }
}
