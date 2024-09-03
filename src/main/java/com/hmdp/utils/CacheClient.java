package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * @author RainSoul
 * @create 2024-09-03
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 根据前缀和ID查询数据，带有穿透处理
     * 当数据既不在缓存中也不在数据库中时，可以避免对数据库的查询
     * 并将这种情况下返回的空结果缓存起来，以避免频繁的空查询
     *
     * @param keyPrefix  缓存键的前缀
     * @param id         数据的唯一标识符
     * @param type       返回对象的类型
     * @param dbFallback 数据库查询的回退函数
     * @param time       缓存空结果的时间
     * @param unit       时间单位
     * @return 查询到的对象，可能为null
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 构建缓存的键
        String key = keyPrefix + id;
        // 尝试从缓存中获取数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 如果缓存中存在数据，则反序列化并返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, type);
        }

        // 如果缓存中存在空字符串，表示该数据不存在
        if (shopJson != null) {
            return null;
        }

        // 数据库查询的回退机制
        R r = dbFallback.apply(id);

        // 如果数据库中也没有数据，则将空结果缓存指定时间
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", time, unit);
            return null;
        }

        // 将查询到的数据存入缓存
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 带有互斥锁的查询方法
     * 本方法用于从Redis缓存中查询数据，如果缓存不存在，则通过互斥锁防止缓存击穿和雪崩效应
     * 主要应用于避免并发请求下对数据库的重复访问
     *
     * @param keyPrefix 缓存键的前缀，用于标识不同的数据类型
     * @param id 数据的唯一标识符，用于区分不同的数据项
     * @param type 返回值的类型，用于将字符串形式的数据转换为对象
     * @param dbFallback 数据库回退函数，当缓存中无数据时调用此函数从数据库获取数据
     * @param time 数据在Redis中的存活时间
     * @param unit 时间单位
     * @return 查询到的数据，经过转换后的对象类型
     */
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 构建缓存键
        String key = keyPrefix + id;
        // 尝试从Redis中获取数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 如果Redis中存在非空数据，则直接反序列化并返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, type);
        }
        // 如果Redis中数据为空字符串，则直接返回null，表示数据不存在
        if (shopJson != null) {
            return null;
        }
        // 构建互斥锁的键
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            // 尝试加锁，防止并发访问
            boolean isLock = tryLock(lockKey);
            // 如果未获取到锁，则稍后重新尝试查询
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 从数据库回退函数中获取数据
            r = dbFallback.apply(id);
            // 如果数据为空，则在Redis中设置空值标志，防止缓存穿透
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", time, unit);
                return null;
            }
            // 将数据序列化并存入Redis
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            unLock(lockKey);
        }
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


}
