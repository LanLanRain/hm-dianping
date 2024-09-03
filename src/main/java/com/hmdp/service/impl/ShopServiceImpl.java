package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 通过ID查询店铺信息
     *
     * @param id 店铺ID
     * @return 包含店铺信息的Result对象
     */
    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
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
    public Shop queryWithMutex(Long id) {
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
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 将数据库查询到的店铺信息写入Redis缓存
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_NULL_TTL, TimeUnit.MINUTES);
        } catch (RuntimeException e) {
            // 处理运行时异常
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            unLock(lockKey);
        }
        return shop;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据逻辑过期策略查询店铺信息
     * 当数据在Redis中存在且未过期时，直接返回店铺信息
     * 若数据已过期，则尝试加锁，并在成功加锁后异步重建缓存
     *
     * @param id 店铺ID
     * @return 店铺信息，若不存在或已过期且加锁失败，则可能返回null
     */
    public Shop queryWithLogicalExpire(Long id) {
        // 构造缓存键
        String key = CACHE_SHOP_KEY + id;
        // 尝试从Redis中获取店铺信息的JSON字符串
        String json = stringRedisTemplate.opsForValue().get(key);
        // 如果JSON字符串为空或空白，则说明缓存中没有对应的数据
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 将JSON字符串反序列化为RedisData对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        // 从RedisData对象中提取店铺信息
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        // 获取数据的过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        // 如果数据未过期，则直接返回店铺信息
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 构造锁的键
        String lockKey = LOCK_SHOP_KEY + id;
        // 尝试获取锁
        boolean isLock = tryLock(lockKey);
        // 如果成功获取锁，则异步重建缓存
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 异步任务：将店铺信息保存到Redis中，并设置过期时间
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    // 异常处理：将异常包装为运行时异常抛出
                    throw new RuntimeException(e);
                } finally {
                    // 无论如何，最终释放锁
                    unLock(lockKey);
                }
            });
        }
        // 返回店铺信息，即使缓存已过期，也先返回旧数据以减少用户等待时间（异步更新缓存）
        return shop;
    }

    public void saveShop2Redis(Long id, Long expireSeconds) {
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
