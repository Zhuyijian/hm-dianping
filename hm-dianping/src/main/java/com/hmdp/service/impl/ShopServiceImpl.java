package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
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
        String redis_shop_id=CACHE_SHOP_KEY+id;

        /*查询Redis缓存中是否有此数据*/
        String shopJson = stringRedisTemplate.opsForValue().get(redis_shop_id);

        /*判断是否不为空，不要判断字符串本身，而是内容*/
        if (StrUtil.isNotBlank(shopJson)){
            /*使用hutTool中的JSON工具，将Redis中存储的json转为对应的Bean*/
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        /*到这没有返回说明Redis中没有，去Mysql中查询*/
        Shop shop = this.getById(id);
        if (shop==null){
            return Result.fail("商家不存在");
        }

        /*将从mysql中查到的商户信息缓存到Redis中*/
        stringRedisTemplate.opsForValue().set(redis_shop_id,JSONUtil.toJsonStr(shop));

        /*返回mysql中查询到的数据*/
        return Result.ok(shop);
    }
}
