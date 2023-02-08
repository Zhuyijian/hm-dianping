package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /**
     * 2022 1-1 0:0 的秒数
     */
    private static final Long BEGIN_TIMESTAMP = 1640995200L;

    /**
     * 时间戳的 左移位数
     */
    private static final int COUNT_BITS = 32 ;


    private final StringRedisTemplate stringRedisTemplate;

    /*因为本类已被Spring接管，所以可以通过构造函数的形式从容器中自动注入*/
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成id
     * @return
     */
    public Long nextId(String keyPrefix){
        /*因为id是由时间戳和序列号拼接组成*/
        /*时间戳由现在时间秒，减去某个时间(这里使用 2022-1-1-0-0)秒得出*/
        LocalDateTime now = LocalDateTime.now();
        long timeStamp = now.toEpochSecond(ZoneOffset.UTC)-BEGIN_TIMESTAMP;

        /*获取序列号，序列号的key 由业务前缀加上当前时间自增得出*/
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        /*这样自增量都是按照每天来进行统计*/
        /*例如2.07是 0 1 2 3  这样自增*/
        /*到了2.08就不是从3继续自增了 而是又从 0 1 2 3开始自增*/
        /*这样做是因为Redis最多存储2的32位数，如果不按天数分割，日积月累几十年很可能到达2的32位*/
        Long count = stringRedisTemplate.opsForValue().increment("inc:" + keyPrefix + ":" + data);


        /*将时间戳左移32位，再通过或的方式将序列号放在左移后的32位中（0和0还是0，1和0就位1）*/
        return timeStamp << COUNT_BITS | count;
    }



}
