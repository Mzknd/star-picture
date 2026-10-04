package org.example.starpicbackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.example.starpicbackend.constant.UserConstant;
import org.example.starpicbackend.exception.BusinessException;
import org.example.starpicbackend.exception.ErrorCode;
import org.example.starpicbackend.model.dto.user.UserQueryRequest;
import org.example.starpicbackend.model.entity.User;
import org.example.starpicbackend.model.enums.UserRoleEnum;
import org.example.starpicbackend.model.vo.LoginUserVO;
import org.example.starpicbackend.model.vo.UserVO;
import org.example.starpicbackend.service.UserService;
import org.example.starpicbackend.mapper.UserMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.DigestUtils;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
* @author 十六夜⭐朔星
* @description 针对表【user(用户)】的数据库操作Service实现
* @createDate 2025-11-24 20:01:38
*/
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService{

    private static final int MAX_PASSWORD_BYTES = 72;
    private static final String LEGACY_SALT = "Sakusei";
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);


    /**
     * 用户注册
     * @param userAccount   用户账户
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @return
     */
    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 1. 校验
        if (StrUtil.hasBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (userPassword.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
        validatePassword(userPassword);
        validatePassword(checkPassword);
        // 2. 检查是否重复
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        long count = this.baseMapper.selectCount(queryWrapper);
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
        }
        // 3. 加密
        String encryptPassword = getEncryptPassword(userPassword);
        // 4. 插入数据
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);
        user.setUserName("无名");
        user.setUserRole(UserRoleEnum.USER.getValue());
        boolean saveResult = this.save(user);
        if (!saveResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
        }
        return user.getId();
    }
    /**
     * 获取加密后的密码
     * @param userPassword
     * @return
     */
    @Override
    public String getEncryptPassword(String userPassword) {
        validatePassword(userPassword);
        return passwordEncoder.encode(userPassword);
    }

    private void validatePassword(String password) {
        if (StrUtil.isBlank(password) || password.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码至少 8 位");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码不得超过 72 个 UTF-8 字节");
        }
    }

    private boolean passwordMatches(String rawPassword, String storedPassword) {
        if (storedPassword == null) {
            return false;
        }
        if (storedPassword.startsWith("$2")) {
            return passwordEncoder.matches(rawPassword, storedPassword);
        }
        if (!storedPassword.matches("[0-9a-fA-F]{32}")) {
            return false;
        }
        byte[] expected = storedPassword.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        String salted = LEGACY_SALT + rawPassword;
        byte[] utf8Hash = DigestUtils.md5DigestAsHex(salted.getBytes(StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.US_ASCII);
        // Preserve the previous runtime-default encoding while making UTF-8 portable.
        byte[] previousHash = DigestUtils.md5DigestAsHex(salted.getBytes())
                .getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, utf8Hash) | MessageDigest.isEqual(expected, previousHash);
    }

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        if (StrUtil.hasBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        validatePassword(userPassword);
        User user = this.baseMapper.selectOne(new QueryWrapper<User>().eq("userAccount", userAccount));
        if (user == null || !passwordMatches(userPassword, user.getUserPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        if (!user.getUserPassword().startsWith("$2")) {
            String oldHash = user.getUserPassword();
            String upgradedHash = passwordEncoder.encode(userPassword);
            int updated = this.baseMapper.update(null, new UpdateWrapper<User>()
                    .eq("id", user.getId()).eq("userPassword", oldHash)
                    .set("userPassword", upgradedHash));
            if (updated == 0) {
                // A concurrent login or password update must not be overwritten.
                User latest = this.baseMapper.selectById(user.getId());
                if (latest == null || !passwordMatches(userPassword, latest.getUserPassword())) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
                }
                user = latest;
            } else {
                user.setUserPassword(upgradedHash);
            }
        }
        request.getSession();
        request.changeSessionId();
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);
        return this.getLoginUserVO(user);
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 先判断是否已登录
        Object userObj = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 从数据库查询（追求性能的话可以注释，直接返回上述结果）
        long userId = currentUser.getId();
        currentUser = this.getById(userId);
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    /**
     * 获取脱敏类的用户信息
     * @param user
     * @return
     */
    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null){
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtils.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    /**
     * 用户登出
     * @param request
     * @return
     */

    @Override
    public boolean userLogout(HttpServletRequest request) {
        // 先判断是否已登录
        Object userObj = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        if (userObj == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未登录");
        }
        // 移除登录态
        request.getSession().removeAttribute(UserConstant.USER_LOGIN_STATE);
        return true;
    }

    /**
     * 获取单个用户信息（脱敏）
     * @param user
     * @return
     */
    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    /**
     * 获取用户信息列表（脱敏）
     * @param userList
     * @return
     */
    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (CollUtil.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    /**
     * 将查询请求转换为Mybatis-Plus的qurryWapper语句
     * @param userQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String userAccount = userQueryRequest.getUserAccount();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ObjUtil.isNotNull(id), "id", id);
        queryWrapper.eq(StrUtil.isNotBlank(userRole), "userRole", userRole);
        queryWrapper.like(StrUtil.isNotBlank(userAccount), "userAccount", userAccount);
        queryWrapper.like(StrUtil.isNotBlank(userName), "userName", userName);
        queryWrapper.like(StrUtil.isNotBlank(userProfile), "userProfile", userProfile);
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), sortOrder.equals("ascend"), sortField);
        return queryWrapper;
    }

    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }



}




