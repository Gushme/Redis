package com.CampusEase.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.CampusEase.dto.Result;
import com.CampusEase.dto.ScrollResult;
import com.CampusEase.dto.UserDTO;
import com.CampusEase.entity.Blog;
import com.CampusEase.entity.Follow;
import com.CampusEase.entity.User;
import com.CampusEase.mapper.BlogMapper;
import com.CampusEase.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.CampusEase.service.IFollowService;
import com.CampusEase.service.IUserService;
import com.CampusEase.utils.RedisConstants;
import com.CampusEase.utils.SystemConstants;
import com.CampusEase.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Autowired
    private IFollowService followService;

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
        records.forEach(blog -> {
            isBlogLiked(blog);
            queryBlogUser(blog);
        });
        return Result.ok(records);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1. 查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        // 2. 查询blog有关的用户
        queryBlogUser(blog);
        // 3. 查询blog是否被点赞过
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if(user == null) {
            return; // 用户未登录，无需查询用户是否点赞
        }
        // 1. 用redis集合判断当前用户是否已经点赞
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        // 1. 用redis集合判断当前用户是否已经点赞
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 2. 若未点赞
        if(score == null){
            // 2.1 数据库中点赞数++
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 2.2 保存用户到redis集合 zadd key value score
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 3. 若已点赞
            // 3.1 点赞数--
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 3.2 把用户从redis集合中移除
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 解析出用户id
        List<Long> ids = top5.stream()
                .map(Long::valueOf) // 将 String 转换为 Long
                .collect(Collectors.toList()); // 收集结果为 List<Long>
        // 根据用户id查询用户
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2. 保存探店博文
        boolean isSuccess = save(blog);
        // 3. 查询笔记作者的所有粉丝
        if(!isSuccess) {
            return Result.fail("新增笔记失败!");
        }
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4. 推送笔记id给所有粉丝
        for(Follow follow : follows) {
            Long userId = follow.getUserId();
            // 推送
            String key = RedisConstants.FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 查询收件箱 ZREVRANGEBYSCORE key max min WITHSCORES LIMIT offset count
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        // 3. 解析数据：blogId、minTime 最小时间戳、offset
        if(typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 4. 根据id查询blog
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 0;
        for(ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 4.1 获取id
            ids.add(Long.valueOf(typedTuple.getValue()));
            // 4.2 获取分数
            long time = typedTuple.getScore().longValue();
            if(time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }

        }
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for(Blog blog : blogs) {
            // 4.3 查询blog有关的用户
            queryBlogUser(blog);
            // 4.4 查询blog是否被点赞过
            isBlogLiked(blog);
        }
        // 5. 封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }
}
