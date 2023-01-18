package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Zhu
 * @since 2023-1-18
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        /*使用正则表达式工具类中的静态方法，判断手机号是否无效*/
        if (RegexUtils.isPhoneInvalid(phone)) {
            /*说明是无效*/
            return Result.fail("手机号格式不合法");
        }
        /*使用maven引入的hutTool这个类提供的静态方法，生成6位的随机数*/
        String code = RandomUtil.randomNumbers(6);

        /*将生成的随机数保存到session中*/
        session.setAttribute("code", code);

        /*发送验证码*/
        log.debug("发送短信验证码成功,验证码:"+code);

        /*返回ok*/
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        /*再次校验手机号是否无效*/
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号格式错误");
        }

        /*校验验证码*/
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if (code==null||!code.equals(cacheCode.toString())){
            return Result.fail("验证码错误");
        }

        /*根据用户手机号查询用户是否存在*/
        LambdaQueryWrapper<User> userLambdaQueryWrapper = new LambdaQueryWrapper<>();
        userLambdaQueryWrapper.eq(User::getPhone,loginForm.getPhone());

        /*通过条件构造器查询user*/
        User user = this.getOne(userLambdaQueryWrapper);

        /*如果用户不存在*/
        if (user == null) {
            /*创建新用户并存入表中*/
            user=createUserWithPhone(loginForm.getPhone());
        }

        /*将userDto对象存入session*/
        /*BeanUtil.copyProperties(源对象，拷贝目标类.class)  会返回一个拷贝后的目标类的对象*/
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        /*需不需要返回值，返回一个ok即可*/
        return Result.ok();
    }

    /**
     * 根据电话号新建要给用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone){
        User user = new User();
        /*新建一个User，并设置它的手机和用户名，用户名前缀使用user_加随机生成的10位字符*/
        user.setPhone(phone);
        /*这里的user_前缀 使用了一个常量保存*/
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));

        /*调用继承的Mp中的保存方法*/
        this.save(user);

        return user;
    }

}
