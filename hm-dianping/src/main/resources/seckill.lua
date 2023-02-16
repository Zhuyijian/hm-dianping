--[[查询指定的优惠券库存，和用户是否下单过，所以优惠券id和用户id都需要通过传输获取到]]
--[[优惠券id]]
local voucherId = ARGV[1]
local userId = ARGV[2]

--[[通过前缀加id就是redis中的键]]
--[[优惠券库存]]
local stockKey= 'seckill:stock:' .. voucherId
--[[订单id，代表已经下过单的用户集合]]
local orderKey= 'seckill:order:' .. voucherId

--[[脚本业务]]
--[[判断库存是否>0]]
if (tonumber(redis.call('get',stockKey))<=0) then
    --[[返回1，说明库存不足]]
    return 1;
end

--[[判断用户是否已经下过单，如果set集合中有此用户id说明已经下过单了]]
if (redis.call('sismember',orderKey,userId)==1) then
    --[[重复下单，返回2]]
    return 2;
end

--[[走到这，说明库存充足，且未下过单]]
--[[库存减一]]
redis.call('incrby',stockKey,-1)

--[[将用户id添加到已购买的集合中]]
redis.call('sadd',orderKey,userId)

--[[运行正常，则返回0]]
return 0
