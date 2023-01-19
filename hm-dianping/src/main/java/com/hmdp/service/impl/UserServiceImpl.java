package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
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
    /*可以使用@Autowired(ByType) 也可以使用@Resouce（ByName）*/
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        /*使用正则表达式工具类中的静态方法，判断手机号是否无效*/
        if (RegexUtils.isPhoneInvalid(phone)) {
            /*说明是无效*/
            return Result.fail("手机号格式不合法");
        }
        /*使用maven引入的hutTool这个类提供的静态方法，生成6位的随机数*/
        String code = RandomUtil.randomNumbers(6);

        /*将随机数保存到Redis中,key为RedisConstants中的login:code:常量*/
        /*设置Redis中code的存在时间,存在时间LOGIN_CODE_TTL也是使用常量管理*/
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        /*发送验证码*/
        log.debug("发送短信验证码成功,验证码:" + code);

        /*返回ok*/
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        /*再次校验手机号是否无效*/
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误");
        }

        /*校验验证码,从redis拿到存储的code*/
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());

        /*从前端表单提交的对象中拿到code*/
        String code = loginForm.getCode();

        if (code == null || !code.equals(cacheCode)) {
            return Result.fail("验证码错误");
        }

        /*根据用户手机号查询用户是否存在*/
        LambdaQueryWrapper<User> userLambdaQueryWrapper = new LambdaQueryWrapper<>();
        userLambdaQueryWrapper.eq(User::getPhone, loginForm.getPhone());

        /*通过条件构造器查询user*/
        User user = this.getOne(userLambdaQueryWrapper);

        /*如果用户不存在*/
        if (user == null) {
            /*创建新用户并存入表中*/
            user = createUserWithPhone(loginForm.getPhone());
        }

        /*将user拷贝成userDto。存取用户基本信息*/
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        /*将userDto以Hash的类型存入Redis*/
        /*将userDto包装成Map，方便Hash类型的存取*/
        /*将bean转换成map的同时 使用setIgnoreNullValue(true)忽略空值，
        .setFieldValueEditor((fileName,fileValue)-> fileValue.toString()对每个键进行操作，我这将么给键的类型改为了String，
         因为UserDto的id是Long,存入Hash中只能String*/
        Map<String, Object> beanToMap = BeanUtil.beanToMap(userDTO,new HashMap<String, Object>(),CopyOptions.create()
                .setIgnoreNullValue(true).setFieldValueEditor((fileName,fileValue)-> fileValue.toString() ));

        /*存入Redis,key为前缀加uuid，加上true是不带-中划线的，isSimple是否简单，简单就是没有中划线*/
        String uuid = UUID.randomUUID().toString(true);
        String token=LOGIN_USER_KEY+uuid;
        stringRedisTemplate.opsForHash().putAll(token,beanToMap);

        /*设置redis中存在的时间，默认为30分钟，因为Session默认也是30分钟*/
        stringRedisTemplate.expire(token,LOGIN_USER_TTL,TimeUnit.MINUTES);

        /*返回redis中存储userTdo的key作为token返回给前端*/
        return Result.ok(uuid);
    }

    /**
     * 根据电话号新建要给用户
     *
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        /*新建一个User，并设置它的手机和用户名，用户名前缀使用user_加随机生成的10位字符*/
        user.setPhone(phone);
        /*这里的user_前缀 使用了一个常量保存*/
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        /*调用继承的Mp中的保存方法*/
        this.save(user);

        return user;
    }

}
