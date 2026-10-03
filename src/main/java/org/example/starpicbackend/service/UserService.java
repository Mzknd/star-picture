package org.example.starpicbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.starpicbackend.model.dto.user.UserQueryRequest;
import org.example.starpicbackend.model.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.starpicbackend.model.vo.LoginUserVO;
import org.example.starpicbackend.model.vo.UserVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
* @author 十六夜⭐朔星
* @description 针对表【user(用户)】的数据库操作Service
* @createDate 2025-11-24 20:01:38
*/
public interface UserService extends IService<User> {


    /**
     * 用户注册
     *
     * @param userAccount   用户账户
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @return 新用户 id
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);


    /**
     * 获取加密后的密码
     * @param userPassword 密码
     * @return 加密后的密码
     */
    String getEncryptPassword(String userPassword);

    /**
     * 用户登录
     *
     * @param userAccount  用户账户
     * @param userPassword 用户密码
     * @param request 前端请求
     * @return 脱敏后的用户信息
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);


    /**
     * 获取当前登录用户
     * @param request 前端请求
     * @return 获取登陆用户信息
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 获得脱敏后的登陆用户信息
     * @param user 用户
     * @return  脱敏后的登陆用户信息
     */
    LoginUserVO getLoginUserVO(User user);

    /**
     * 用户注销
     *
     * @param request
     * @return
     */
    boolean userLogout(HttpServletRequest request);

    /**
     * 获取单个用户信息（脱敏）
     * @param user
     * @return
     */
    UserVO getUserVO(User user);
    /**
     * 获取用户信息列表（脱敏）
     * @param userList
     * @return
     */
    List<UserVO> getUserVOList(List<User> userList);
    /**
     * 将查询请求转换为Mybatis-Plus的qurryWapper语句
     * @param userQueryRequest
     * @return
     */
    QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest);


    /**
     * 是否为管理员
     *
     * @param user
     * @return
     */
    boolean isAdmin(User user);

}
