package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Zhu
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
//           填充用户信息
            this.queryBlogUser(blog);
            //        判断此博客是否被当前用户点赞
            this.isLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
//        通过blogId查询到对应的博客
        Blog blog = getById(id);

        if (blog == null){
            return Result.fail("博客不存在");
        }
//        查询根据id查询博主的网名和图像并set到Blog中
        queryBlogUser(blog);
//        判断此博客是否被当前用户点赞
        isLiked(blog);

        return Result.ok(blog);
    }

    /**
     * 判断博客是否点赞过
     * @param blog
     */
    private void isLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        String key = BLOG_LIKED_KEY+blog.getId();

        Double member = stringRedisTemplate.opsForZSet().score(key,user.getId().toString());

//        在Zset中分数不等于空说明用户点赞过
        blog.setIsLike(member!=null);

    }

    /**
     * 点赞博客
     * @param id  博客id
     */
    @Override
    public Result likeBlog(Long id) {
//        获取当前用户的id
        Long userId = UserHolder.getUser().getId();

//        确定redis的key前缀未blog:liked加博客id
        String key = BLOG_LIKED_KEY+id;
//        判断当前用户是否点赞
        Double member = stringRedisTemplate.opsForZSet().score(key, userId.toString());
//        如果未点赞，则数据库点赞数+1，且将用户信息放入Redis的Set集合中
        if (member==null){
            boolean update = update().setSql("liked = liked + 1").eq("id", id).update();
            if (update){
//                Zset中，排序的分数按照时间戳来，添加越晚时间戳越大，则排序越后
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
//        如果已点赞，则数据库点赞数-1，且将用户信息从Redis的Set集合中移除
            boolean update = update().setSql("liked = liked-1").eq("id", id).update();
            if (update){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 获取点赞排行版最早点赞的前五名
     * @param id blogId，通过博客id在Redis中查询点赞的用户，拿到Zset的前五
     * @return
     */
    @Override
    public Result queryLikedTop5(Long id) {
        String key = BLOG_LIKED_KEY+id;
//        获取到所有用户的id，为String的类型
        Set<String> userIds = stringRedisTemplate.opsForZSet().range(key, 0, 4);

//       如果查询到userId集合为空，说明没有人点赞过，返回一个空集合即可
        if (userIds==null || userIds.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

//        将用户Id都转换为Long类型的List，方便在数据库中进行查询
        List<Long> userList = userIds.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
//        将用户id使用,进行链接
        String join = StrUtil.join(",", userList);

//        通过id集合在数据库中进行查询，需要指定查询的顺序，例如id5是先点赞的，1是后点赞的，返回的顺序也是要5在前1在后，
//        但是,mysql默认返回1在前5在后，所以需要Field手动指定：以id排序，顺序为我们从Redis中查询出的字符串
//        select * from user where id in (5,1) order by FIELD (id,5,1,.,.)
//        .last方法，在原有的基础上，拼接上最后一条Sql
//        最后将从数据库拿到的字符串从过Stream流转为UserDto类型返回
        List<UserDTO> userDTOList = userService.query().in("id", userIds).last("order by Field (id," + join + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOList);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
