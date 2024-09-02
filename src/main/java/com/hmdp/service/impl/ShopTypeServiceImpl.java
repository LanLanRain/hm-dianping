package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 查询类型列表，优先从缓存中读取
     *
     * @return 返回查询结果的类型列表
     */
    @Override
    public Result queryTypeList() {
        // 定义缓存键值
        String key = CACHE_SHOP_KEY;
        // 判断缓存中是否存在数据
        if (redisTemplate.hasKey(key)) {
            // 如果缓存中存在数据，则直接返回缓存中的数据
            return Result.ok(redisTemplate.opsForValue().get(key));
        }
        // 如果缓存中不存在数据，则从数据库中查询，并存入缓存
        redisTemplate.opsForValue().set(key, query().list());
        // 返回从缓存中获取的数据
        return Result.ok(redisTemplate.opsForValue().get(key));
    }

}
