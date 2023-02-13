package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private static final String KEY_PREFIX = "lock:";

    /*锁标识的前缀*/
    private static final String ID_PREFIX = UUID.randomUUID().toString()+"-";

    private String name;
    private StringRedisTemplate stringRedisTemplate;


    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

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
        /*释放锁之前需要判断Redis中存的标识是否是本线程的表示，防止误释放*/
        String threadId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);

        /*常量对比Redis中的锁内容*/
        if (ID_PREFIX.equals(threadId)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
