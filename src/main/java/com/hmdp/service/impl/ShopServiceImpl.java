package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 通过ID查询店铺信息
     *
     * @param id 店铺ID
     * @return 包含店铺信息的Result对象
     */
    @Override
    public Result queryById(Long id) {
        // 生成缓存用的键
        String key = CACHE_SHOP_KEY + id;
        // 尝试从Redis缓存中获取店铺信息的JSON字符串
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 如果缓存中存在该店铺信息
        if (StrUtil.isNotBlank(shopJson)) {
            // 将JSON字符串转换为Shop对象
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            // 直接返回缓存中的店铺信息
            return Result.ok(shop);
        }

        // 如果缓存中没有，那么从数据库中查询店铺信息
        Shop shop = getById(id);
        // 如果数据库中也不存在该店铺信息
        if (shop == null) {
            // 返回失败结果，说明店铺不存在
            return Result.fail("店铺不存在");
        }

        // 将数据库中查询到的店铺信息存入Redis缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 返回数据库中的店铺信息
        return Result.ok(shop);
    }

    /**
     * 更新店铺信息
     * 当更新店铺信息时，先检查店铺ID是否为空，因为ID是进行后续操作的必要条件
     * 如果ID为空，则返回失败结果并提示错误信息
     * 如果ID不为空，则调用父类方法更新数据库中的店铺信息，并从缓存中删除该店铺的信息，
     * 这样下次请求时会从数据库中同步最新的店铺信息到缓存
     *
     * @param shop 需要更新的店铺对象
     * @return 更新操作的结果，包含操作是否成功的信息
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        // 获取店铺ID
        Long id = shop.getId();
        // 检查店铺ID是否为空
        if (id == null) {
            // 如果ID为空，返回失败结果并提示错误信息
            return Result.fail("店铺ID不能为空");
        }
        // 更新数据库中的店铺信息
        updateById(shop);
        // 从缓存中删除该店铺的信息，以保证数据一致性
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        // 返回成功结果
        return Result.ok();
    }

}
