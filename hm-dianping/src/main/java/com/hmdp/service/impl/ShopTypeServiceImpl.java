package com.hmdp.service.impl;

import cn.hutool.core.stream.StreamUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
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
        /*通过key去Redis缓存中获取列表,因为分类列表只有一个，就不用id做唯一性了*/
        String list = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);

        /*如果有缓存则直接以json的形式返回*/
        if (StrUtil.isNotBlank(list)){
            return Result.ok(list);
        }

        /*如果到这说明Redis缓存中没有分类列表，需要从mysql中查询*/
        List<ShopType> typeList = this.list();
        if (typeList==null&&typeList.size()!=0){
            return Result.fail("没有找到分类列表");
        }

        /*到这说明拿到的list中有内容，将遍历将它的每一个元素转为json后存一个List放入缓存后返回*/
        List<String> cacheTypeList = typeList.stream().map(shopType -> {
            return JSONUtil.toJsonStr(shopType);
        }).collect(Collectors.toList());

        System.out.println(cacheTypeList);


        return Result.ok(cacheTypeList);
    }
}
