package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Zhu
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    /*优惠券的服务实现类*/
    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 秒杀下单，创建订单
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        /*通过id查询优惠券信息*/
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);

        /*根据优惠券信息判断秒杀是否开始*/
        /*未开始或过期则返回异常*/

        /*开始日期在当前之后说明秒杀未开始*/
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始!");
        }

        /*截至日期在当前日期之前说明秒杀已结束*/
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束！");
        }


        /*判断库存是否充足*/
        /*不过库存不足则返回异常*/
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        /*从线程域中拿到用户id*/
        Long userId = UserHolder.getUser().getId();


        /*尝试获取Redis分布锁*/
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        boolean lock = simpleRedisLock.tryLock(10L);

        /*还是使用反向判断，判断如果没有获取到锁*/
        if (!lock) {
            /*说明是同一个用户id，返回错误信息即可*/
            return Result.fail("服务器繁忙");
        }
        /*到这说明已经获取到了锁对象，运行业务逻辑*/
        /*此时的this是锁住的对象，不具有事务功能，所以需要手动获取Spring的aop对象 通过aop对象后手动调用*/
        /*这样就可以事务提交后再释放锁*/
        try {
            IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();
            return currentProxy.createVoucherOder(voucherId, userId);
        } finally {
            simpleRedisLock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOder(Long voucherId, Long userId) {
        /*如果是抢购大优惠券，则需要判断用户是否已经下过单*/
        Long count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId).count();

        if (count > 0) {
            return Result.fail("已下过单,不能重复");
        }


        /*库存充足则库存减一后创建订单*/
        boolean isSuccess = iSeckillVoucherService.update()
                .setSql("stock = stock-1")
                .eq("voucher_id", voucherId)
                /*乐观锁，库存大于0可成功*/
                .gt("stock", 0)
                .update();

        log.debug("" + isSuccess);

        /*如果没成功*/
        if (!isSuccess) {
            return Result.fail("库存不足!");
        }


        /*创建订单（往订单表中创建数据）*/
        VoucherOrder voucherOrder = new VoucherOrder();

        /*填充订单id，代金券id，用户id*/
        /*使用全局id生成器生成id*/
        Long orderId = redisIdWorker.nextId("order");


        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        /*使用MP将订单类写入数据库*/
        save(voucherOrder);

        /*返回订单id*/
        return Result.ok(orderId);
    }
}
