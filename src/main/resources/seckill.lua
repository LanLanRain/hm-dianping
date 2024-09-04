-- 从命令行参数中获取优惠券ID和用户ID
local voucherId = ARGV[1]
local userId = ARGV[2]

-- 构建存储优惠券库存和订单的键
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 检查优惠券库存，如果库存为零或负数，则返回错误代码1
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end

-- 检查用户是否已经下单，如果已下单，则返回错误代码2
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 扣减库存并记录用户订单
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
