package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.apache.ibatis.plugin.Interceptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
import static net.sf.jsqlparser.util.validation.metadata.NamedObject.user;

public class LoginInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    /*因为LoginInterceptor使用时需要我们手动new对象加入Web拦截器中，没有被Spring管理，不能使用依赖注入*/
    /*使用构造函数，当使用LoginInterceptor对象加入Web拦截器时，再通过构造方法传入StringRedisTemplate对象*/
    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        /*拿到Redis中的替代session的token信息(uuid),key是每次请求头中的token  request.getHeader(authorization)即可获取到 */
        String token = request.getHeader("authorization");
        /*如果拿到的token为空，说明是未登录*/
        if (StrUtil.isBlank(token)) {
            response.setStatus(401);
            return false;
        }

        /*获取此用户 redis中所有的键值对*/
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY+token);
        /*将map转回Bean*/
        UserDTO user = BeanUtil.fillBeanWithMap(entries, new UserDTO(), false);


        /*拿到redis中的UserDtoMap 为空Map说明当前是未登录状态，不放行*/
        if (entries.isEmpty()) {
            response.setStatus(401);
            return false;
        }

        /*到这说明已经登录，将用户数据放入ThreadLocal,UserHolder类中封装了对ThreadLocal的操作*/
        UserHolder.saveUser(user);

        /*刷新在redis中存在的时间，因为是无操作30分钟才销毁用户数据*/
        /*只要发送了请求，请求在拦截器放行前刷新redis中的时间*/
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL, TimeUnit.MINUTES);

        /*放行*/
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        /*删除在ThreadLocal中的用户数据*/
        UserHolder.removeUser();
    }
}
