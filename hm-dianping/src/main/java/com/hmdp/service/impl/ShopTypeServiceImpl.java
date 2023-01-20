package com.hmdp.service.impl;

import cn.hutool.core.stream.StreamUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Zhu
 * @since 2023-1-20
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {
        /*返回给前端的类型是list类型*/
        /*是否命中缓存*/
        String cacheShopTypeKey = stringRedisTemplate.opsForValue().get("CACHE_SHOP_TYPE_KEY");

        /*命中缓存，将json转为list返回给前端*/
        if (StrUtil.isNotBlank(cacheShopTypeKey)){
            return Result.ok(JSONUtil.toList(cacheShopTypeKey,ShopType.class));
        }

        /*没有命中，根据成绩倒序查询查询数据库*/
        LambdaQueryWrapper<ShopType> shopTypeLambdaQueryWrapper = new LambdaQueryWrapper<>();
        shopTypeLambdaQueryWrapper.orderByAsc(ShopType::getSort);
        List<ShopType> list = this.list(shopTypeLambdaQueryWrapper);

        /*判断list中有没有内容*/
        if (list.size()==0){
            /*没有内容则返回错误信息*/
            return Result.fail("未找到店铺分类");
        }

        /*将list转成json的形式存在Redis中*/
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(list));

        /*将数据库查到的list返回个前端*/
        return Result.ok(list);
    }
}
