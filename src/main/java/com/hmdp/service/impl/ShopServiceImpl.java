package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
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

import static com.hmdp.utils.RedisConstants.*;

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
        Shop shop = queryWithMutex(id);
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

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 根据ID查询店铺信息，使用互斥锁机制防止缓存击穿
     * 当缓存中不存在对应ID的店铺信息时，通过加锁避免多个线程同时查询数据库
     * 只有获取锁成功的线程会进行数据库查询，其他线程将尝试等待或直接返回错误信息
     *
     * @param id 店铺ID
     * @return 店铺信息，如果缓存和数据库中都不存在，则返回null
     */
    public Shop queryWithMutex(Long id){
        // 尝试从Redis缓存中获取店铺信息的JSON字符串
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 如果缓存中存在该店铺的JSON信息且不为空，则将其转换为Shop对象并返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 如果缓存中存在该键但值为空字符串，表示之前已检查过数据库，直接返回null
        if (shopJson != null) {
            return null;
        }
        // 构造锁的键
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            // 尝试获取互斥锁
            boolean isLock = tryLock(lockKey);
            // 如果未获取到锁，线程微睡眠后重试查询方法
            if (!isLock) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return queryWithMutex(id);
            }
            // 获取锁成功后，从数据库中查询店铺信息
            shop = getById(id);
            // 如果数据库中也不存在该店铺信息，将空值写入Redis并返回null
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            // 将数据库查询到的店铺信息写入Redis缓存
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_NULL_TTL,TimeUnit.MINUTES);
        } catch (RuntimeException e) {
            // 处理运行时异常
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            unLock(lockKey);
        }
        return shop;
    }
}
