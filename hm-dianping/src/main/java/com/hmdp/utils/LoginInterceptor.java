package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.apache.ibatis.plugin.Interceptor;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        /*拿到session的User*/
        Object user = request.getSession().getAttribute("user");

        /*拿到session中的user为空说明当前是未登录状态，不放行*/
        if (user == null) {
            response.setStatus(401);
            return false;
        }

        /*到这说明已经登录，将用户数据放入ThreadLocal,UserHolder类中封装了对ThreadLocal的操作*/
        UserHolder.saveUser((UserDTO) user);

        /*放行*/
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        /*删除在ThreadLocal中的用户数据*/
        UserHolder.removeUser();
    }
}
