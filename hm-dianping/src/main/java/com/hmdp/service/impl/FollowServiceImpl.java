package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    /**
     * 关注用户
     * @param userId  目标用户的id
     * @param isFollow false则为取关，true则为关注
     * @return
     */
    @Override
    public Result follow(Long userId, Boolean isFollow) {
//        获取当前用户Id
        UserDTO user = UserHolder.getUser();
        Long myUserId = user.getId();

//     判断前端传来的用户id的有效性
        if (userId==null){
            return Result.ok();
        }

//        判断是关注还是取关操作
        if (isFollow){
//            关注操作
            Follow follow = new Follow();
            follow.setUserId(myUserId);
            follow.setFollowUserId(userId);
            save(follow);
        }else {
//            取关操作
            LambdaUpdateWrapper<Follow> followLambdaUpdateWrapper = new LambdaUpdateWrapper<>();
            followLambdaUpdateWrapper.eq(Follow::getUserId,myUserId).eq(Follow::getFollowUserId,userId);
            remove(followLambdaUpdateWrapper);
        }

        return Result.ok();
    }

    /**
     * 查询是否关注
     * @param userId
     * @return
     */
    @Override
    public Result isFollow(Long userId) {
        UserDTO user = UserHolder.getUser();
//        if (user == null){
//            return Result.ok();
//        }
        Long myUserId = user.getId();

        LambdaQueryWrapper<Follow> followLambdaQueryWrapper = new LambdaQueryWrapper<>();
        followLambdaQueryWrapper.eq(Follow::getUserId,myUserId).eq(Follow::getFollowUserId,userId);
        long count = count(followLambdaQueryWrapper);

//        如果count的结果大于0 说明已关注,反之没关注
        return Result.ok(count>0);
    }
}
