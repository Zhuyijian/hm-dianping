package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.RedisConfig;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Zhu
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    /*优惠券的服务实现类*/
    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /*Redisson分布式锁配置对象*/
    @Resource
    private RedisConfig redisConfig;

    @Resource
    private RedisIdWorker redisIdWorker;

//    声明代理对象
    private IVoucherOrderService proxy;

    /*脚本对象声明和初始化*/
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        /*初始化脚本对象并设置地址和返回值*/
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //    新建阻塞队列，参数是初始化的大小
    private BlockingQueue orderTasks = new ArrayBlockingQueue<VoucherOrder>(1024 * 1024);

    //    线程池，便于新开线程
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newFixedThreadPool(10);

    //    注解作用：类初始化后，运行的方法
    @PostConstruct
    private void init() {
//     类初始化后，新开一个线程运行我们自己写的逻辑
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOderHandler());
    }

    //    定义一个匿名内部类，实现任务接口，写出另开线程运行的逻辑
    private class VoucherOderHandler implements Runnable {

        @Override
        public void run() {
            //       此逻辑一运行就会不停的尝试从阻塞队列中拿voucherOrder对象(因为是阻塞队列，如果发现队列中没有元素则会阻塞，不会一直while)
            while (true) {
                try {
                    /*获取阻塞队列中的订单对象*/
                    VoucherOrder voucherOrder = (VoucherOrder) orderTasks.take();
                    //提交订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("订单存储至数据库中时发生错误", e);
                }
            }
        }
    }

    //  提交订单至数据库业务代码
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
//        此时因为是新开的子线程，是无法从ThreadLocal中获取UserId的，而只能冲传过来的订单对象中获取
        Long userId = voucherOrder.getUserId();
//        获取锁
        RedissonClient redissonClient = redisConfig.redissonClient();

//        获取锁，锁的对象是userid
        RLock lock = redissonClient.getLock("order:" + userId);
        boolean tryLock = lock.tryLock();

        if (!tryLock){
            log.error("出现了重复下单的可能");
        }

//        原本是通过获取代理对象。通过事务来处理。但是在子线程中是无法获取的，只能在主线程中提前获取
        try {
//            通代理对象来提交订单具有事务功能，并且代理对象必须是主线程才能获取的
            proxy.createVoucherOder(voucherOrder);
        } finally {
            lock.unlock();
        }

    }


    /**
     * 秒杀下单，创建订单
     
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
//        执行lua脚本，因为不需要传key，所以第二个参数传了一个空list而不是null
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString());

//        将lua脚本返回值转为int便于判断,如果lua脚本返回的不是0，说明可能是库存不足或者已经重复下单过
        int resultInt = result.intValue();

        if (resultInt != 0) {
            return Result.fail(resultInt == 1 ? "库存不足" : "不允许重复购买");
        }

//        到这说明购买正常，生成订单号便于返回给下单用户和封装订单信息
        Long orderId = redisIdWorker.nextId("order");
//        封装订单信息，另开线程，阻塞队列 异步保存到数据库中
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

//        将初始化好的订单存入阻塞队列，此时秒杀业务已经结束了，剩下的交给阻塞队列和异步线程执行
        orderTasks.add(voucherOrder);

//        获取代理对象，方便订单的事务提交
        proxy = (IVoucherOrderService) AopContext.currentProxy();



        return Result.ok(orderId);
    }


    @Transactional
    public void createVoucherOder(VoucherOrder voucherOrder) {

        /*虽然在Redis中已经判断过库存和用户是否重复下单，但是为了保险还是在数据库的层面上再进行判断一次*/
        Long userId = voucherOrder.getUserId();
        /*如果是抢购大优惠券，则需要判断用户是否已经下过单*/
        Long count = query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId()).count();

        if (count > 0) {
            log.error("用户重复下单");
            return;
        }


        /*库存充足则库存减一后创建订单*/
        boolean isSuccess = iSeckillVoucherService.update()
                .setSql("stock = stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                /*乐观锁，库存大于0可成功*/
                .gt("stock", 0)
                .update();

        log.debug("" + isSuccess);

        /*如果没成功*/
        if (!isSuccess) {
            log.error("库存不足");
        }



        /*使用MP将订单类写入数据库*/
        save(voucherOrder);

    }
}
