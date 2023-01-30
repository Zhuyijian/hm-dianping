package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

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

    @Override
    public Result queryById(Long id) {
        /*加上Redis前缀后的id*/
        String redis_shop_id = CACHE_SHOP_KEY + id;

        /*查询Redis缓存中是否有此数据*/
        String shopJson = stringRedisTemplate.opsForValue().get(redis_shop_id);

        /*判断是否不为空，不要判断字符串本身，而是内容*/
        if (StrUtil.isNotBlank(shopJson)) {
            /*使用hutTool中的JSON工具，将Redis中存储的json转为对应的Bean*/
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        /*上面的判断没有走掉，说明可能有shopJson这个键值对，但是它的值为""*/
        if (shopJson != null) {
            /*说明数据据中没有此数据，这是为了防止缓存穿透设置的空值*/
            return Result.fail("没有找到此商家信息");
        }



        /*到这没有走掉说明Redis中没有此缓存，则去Mysql中查询，进行缓存重建*/
        /*在去mysql中查询之前，需要获取互斥锁，避免缓存穿透，给数据库直接带来压力*/
        Shop shop = null;
        try {
            /*互斥锁的键为前缀加上店铺id，保证店铺id每次只有一个请求进行访问*/
            if (!this.tryLock(LOCK_SHOP_KEY + id)) {
                /*如果没有获取到互斥锁，则进入休眠再次尝试获取*/
                do {
                    Thread.sleep(500);
                } while (!this.tryLock(LOCK_SHOP_KEY + id));
            }

            /*到这说明已经成功获取到了互斥锁，可以进行下一步查询*/
            shop = this.getById(id);

            if (shop == null) {
                /*数据库找不到此数据，设置一个空字符串在Redis中，时间为2分钟，目的是防止 不断发起查询数据库中不存在的数据，导致缓存穿透*/
                stringRedisTemplate.opsForValue().set(redis_shop_id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("商家不存在");
            }

            /*将从mysql中查到的商户信息缓存到Redis中*/
            /*并添加缓存过期时间为30分钟*/
            stringRedisTemplate.opsForValue().set(redis_shop_id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            /*最终在finally中释放锁*/
            this.unLock("shop");
        }

        /*返回mysql中查询到的数据*/
        return Result.ok(shop);
    }

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

    /**
     * 尝试获取互斥锁
     *
     * @return true为获取成功 false为获取失败
     */
    public boolean tryLock(String lock) {
        /*判断Redis中是否有互斥锁的的值，返回false说明已经有了，需要等待释放*/
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lock, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);

        /*将包装类Boolean转成普通类型再返回，避免空指针异常*/
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     */
    public void unLock(String lock) {
        stringRedisTemplate.delete(lock);
    }

}
