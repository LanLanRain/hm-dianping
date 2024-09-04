package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 100);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    handlerVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("订单处理异常");
                }
            }
        }
    }

    private IVoucherOrderService proxy;

    /**
     * 处理优惠券订单
     * 此方法通过分布式锁机制确保用户在同一时间只能创建一个订单，以防止重复下单
     *
     * @param voucherOrder 优惠券订单信息
     */
    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户ID
        Long userId = voucherOrder.getUserId();
        // 根据用户ID生成唯一锁，防止并发下单
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = lock.tryLock();

        // 如果未获取到锁，表明用户正在尝试重复下单
        if (!isLock) {
            // 记录错误日志，不允许重复下单
            log.error("不允许重复下单");
            return;
        }

        try {
            // 调用服务创建优惠券订单
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }


    /**
     * 秒杀优惠券方法
     * <p>
     * 该方法允许用户在指定的时间范围内秒杀特定的优惠券
     * 它会检查秒杀是否已经开始、是否已经结束以及库存是否充足
     * 如果所有检查都通过，它会尝试为当前用户创建一个优惠券订单
     *
     * @param voucherId 优惠券ID
     * @return 操作结果，包括是否成功和相关消息
     */
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        /* // 根据券ID获取秒杀券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        // 检查秒杀是否已经开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }

        // 检查秒杀是否已经结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }

        // 检查秒杀券库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        // 获取当前用户ID
        Long userId = UserHolder.getUser().getId();
        // 创建Redis锁对象，用于防止用户重复下单
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean isLock = lock.tryLock();
        // 如果未获取到锁，表示用户正在尝试重复下单
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }
        try {
            // 获取代理对象，用于调用切面方法
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 调用切面方法创建优惠券订单
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        } */

        Long userId = UserHolder.getUser().getId();

        // 执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );

        int i = result.intValue();
        if (i != 0) {
            return Result.fail(i == 1 ? "库存不足" : "不可重复下单");
        }
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        orderTasks.add(voucherOrder);

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        synchronized (userId.toString().intern()) {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                // return Result.fail("用户已经购买过一次！");
                return;
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherOrder).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                // return Result.fail("库存不足！");
                return;
            }
            save(voucherOrder);
        }
    }


}
