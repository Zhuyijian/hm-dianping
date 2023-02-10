package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Zhu
 * @since 2023-1-20
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {

        Shop shop = null;
        /*解决缓存穿透问题*/
        /*shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, new Function<Long, Shop>() {
            @Override
            public Shop apply(Long id) {
                return getById(id);
            }
        }, CACHE_SHOP_TTL, TimeUnit.MINUTES);*/

        /*通过互斥锁解决缓存击穿的问题*/
//        shop = this.queryByLock(id);z

        /*逻辑过期解决缓存击穿*/
        shop = cacheClient.queryWithLogicTime(CACHE_SHOP_KEY, id, Shop.class, new Function<Long, Shop>() {
            @Override
            public Shop apply(Long aLong) {
                return getById(aLong);
            }
        }, CACHE_SHOP_TTL, TimeUnit.MINUTES);


        /*返回mysql中查询到的数据*/

        return Result.ok(shop);
    }



    /*创建线程池*/
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    /**
     * 逻辑过期解决缓存击穿
     */
    /*public Shop queryWithLogicalExpire(Long id){
        *//*根据id获取redis中的对象json*//*
        String shopJSON = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        *//*判断查询到json对象中是否有内容*//*
        if (StrUtil.isBlank(shopJSON)){
            *//*为空直接返回*//*
            return null;
        }

        *//*将查询到的json转为对象*//*
        RedisData redisData = JSONUtil.toBean(shopJSON, RedisData.class);

        *//*将RedisData中Object对象从json转为对象*//*
        *//*因为json中的Object类型中的对象反序列化后是JSONObject，所以需要将JSONObject实例化*//*
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        *//*判断逻辑时间是否过期*//*
        *//*判断是否在当前时间之后，在之后说明没过期，例如过期时间是11.30，现在时间是11.00，所以过期时间是在当前时间之后，说明没过期*//*
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())){
            *//*没逻辑过期的数据则可以直接返回*//*
            return shop;
        }

        *//*上面那行说明已经过期，需要缓存重建。*//*
        *//*判断是否获取到了互斥锁,这里不需要循环去拿，拿不到直接走，返回旧数据即可，拿到锁就进行缓存重建，重新设置逻辑时间*//*
        if (this.tryLock(LOCK_SHOP_KEY+id)){
            *//*获取到锁后，再次确定缓存逻辑时间已过期*//*
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())){
                *//*没逻辑过期的数据则可以直接返回*//*
                return shop;
            }

            *//*获取互斥锁，再新开一个线程，进行缓存重建*//*
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    ShopServiceImpl.this.unLock(LOCK_SHOP_KEY + id);
                }
            });


        }

        *//*返回过期的商铺信息*//*
        return shop;
    }*/

    /*修改数据库后，删除缓存。必须同时成功或失败，所以添加事务*/
    @Override
    @Transactional
    public Result update(Shop shop) {

        /*确保传来的店铺不为空*/
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("找不到修改的商铺");
        }

        /*更新数据库中的商铺信息*/
        this.updateById(shop);

        /*删除对应商铺的缓存*/
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }





}
