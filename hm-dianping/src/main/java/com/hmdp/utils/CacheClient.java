package com.hmdp.utils;

import ch.qos.logback.core.util.TimeUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将任意类型的对象转为json后存储在String类型的key中，并可以设置TTl过期时间
     */
    public void set(String key, Object value, Long TTL, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), TTL, unit);
    }

    /**
     * 存储后，设置逻辑过期时间
     * @param key
     * @param value
     * @param TTL
     */
    public void setWithLogicalExpire(String key, Object value, Long TTL, TimeUnit unit) {
        log.debug("进入了setWithLogicalExpire");
        RedisData redisData = new RedisData();
        redisData.setData(value);
        /*设置过期事件为当前时间加上指定的秒，将用户传的时间格式统一转成秒的形式*/
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(TTL)));
        /*存入Redis*/
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型
     * <R,ID>声明 R和id两个泛型,不确定 返回类型，所以为R，不确定id类型  所以为ID泛型
     *
     * @Param type 自定义传入的反序列化类型
     * @Param id 任意类型的id，可能是String,Long。。。
     * @Param function 确定缓存中没有此数据时，业务层传来的逻辑，因为我们不知道用哪个业务层查，需要根据返回类型进行查询,可能是ShopService....
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> function,
                                          Long time, TimeUnit timeUnit) {
        /*确定获取的id*/
        String key = keyPrefix + id;
        /*从Redis中获取到缓存*/
        String json = stringRedisTemplate.opsForValue().get(key);
        /*如果不为空则直接返回反序列化后的缓存*/
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        /*判断是否存在，如果存在说明其中内容是""，已经缓存了空值*/
        if (json != null) {
            return null;
        }

        /*到这没走则需要查询数据库了*/
        /*因为是工具类，我们不知道调哪个业务层，可能是商铺可能是用户，可能是商铺列表....*/
        /*所以需要调用者传逻辑，可以通过Function来传，函数式编程*/
        R apply = function.apply(id);

        /*判断数据库中查询到的是否为空*/
        if (apply == null) {
            /*为空则设置缓存空值后直接返回  并设置空值存在时间*/
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        /*不为空，加入缓存，并设置时间，时间需要调用者传递， 因为并不是每个缓存都是固定时间*/
        /*调用自己工具类写好的方法进行缓存添加*/
        this.set(key, apply, time, timeUnit);

        return apply;
    }

    /*线程池*/
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 通过逻辑过期解决缓存击穿
     *
     * @param prefix
     * @param id
     * @param rClass
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithLogicTime(String prefix, ID id, Class<R> rClass, Function<ID, R> function, Long expireTime, TimeUnit timeUnit) {
        String key = prefix + id;
        /*因为是逻辑过期，所以Redis中一定有缓存*/
        String json = stringRedisTemplate.opsForValue().get(key);

        /*判断是否是个空json*/
        if (StrUtil.isBlank(json)) {
            /*直接返回空*/
            return null;
        }
        System.out.println(json);

        /*将json转换为RedisData对象*/
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        /*将RedisData中的存储的对象（Object），强转为jsonObject后转为传过来的指定类型*/
        R result = JSONUtil.toBean((JSONObject) redisData.getData(), rClass);

        System.out.println(redisData);

        /*判断redis中的逻辑时间是否过期*/
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            /*过期时间在当前时间之后，说明没过期，则直接返回对象*/
            return result;
        }

        /*到这说明以及过期，需要通过线程重建缓存，重设过期时间*/
        /*防止多个线程同时重建缓存，所以需要获取锁*/
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                R apply = null;
                try {
                    apply = function.apply(id);
                    /*将查询到的对象，包装成RedisData，设置逻辑时间，序列化成json后存入Redis*/
                    this.setWithLogicalExpire(key, apply, expireTime, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }

        return result;

    }

    public boolean tryLock(String lock) {
        /*通过Redis不可修改已存在的方式，模拟锁*/
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(lock, "1", CACHE_SHOP_TTL, TimeUnit.MINUTES);

        /*将包装类转换为普通类型*/
        return BooleanUtil.isTrue(aBoolean);
    }

    public void unLock(String lock) {
        stringRedisTemplate.delete(lock);
    }


}