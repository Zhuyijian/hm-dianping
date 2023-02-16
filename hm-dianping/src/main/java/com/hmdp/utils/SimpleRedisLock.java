package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private static final String KEY_PREFIX = "lock:";

    /*锁标识的前缀*/
    private static final String ID_PREFIX = UUID.randomUUID().toString()+"-";

    /*锁名称key*/
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    /*初始化lua使用的对象*/
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    /*通过静态代码块初始化lua*/
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);

    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /*阻塞队列对象 ， 阻塞队列：有元素时队列进行工作，无元素则进入阻塞状态*/



    /**
     * 尝试获取锁
     * @param timeoutSec
     * @return
     */
    @Override
    public boolean tryLock(Long timeoutSec) {
        /*获取线程的id，作为Redis分布式锁的值，由前缀加上线程id即可*/
        String theadId = ID_PREFIX + Thread.currentThread().getId();

        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, theadId, timeoutSec, TimeUnit.SECONDS);

        /*不直接返回 Boolean包装类，因为自动拆线可能有空指针问题*/
        /*可以使用hutTools的BeanUtils.isTrue。也可以使用Boolean常见True进行比较，返回的也是普通类型*/
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁
     */
    @Override
    public void unlock() {
        /*调用lua脚本*/
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
}
