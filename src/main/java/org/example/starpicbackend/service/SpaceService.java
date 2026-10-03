package org.example.starpicbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.starpicbackend.model.dto.space.SpaceAddRequest;
import org.example.starpicbackend.model.dto.space.SpaceQueryRequest;
import org.example.starpicbackend.model.entity.Space;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.starpicbackend.model.entity.User;
import org.example.starpicbackend.model.vo.SpaceVO;

import javax.servlet.http.HttpServletRequest;

/**
* @author 十六夜⭐朔星
* @description 针对表【space(空间)】的数据库操作Service
* @createDate 2025-12-20 18:35:44
*/
public interface SpaceService extends IService<Space> {
    /**
     * 创建空间
     * @param spaceAddRequest
     * @param loginUser
     * @return
     */
    void deleteSpace(long id, User loginUser);

    long addSpace(SpaceAddRequest spaceAddRequest, User loginUser);

    /**
     * 获取查询对象
     * @param spaceQueryRequest
     * @return
     */
    QueryWrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest) ;

    /**
     *获取空间包装类（单条）
     * @param space
     * @param request
     * @return
     */
    SpaceVO getSpaceVO(Space space, HttpServletRequest request);

    /**
     * 获取空间包装类（分页）
     * @param spacePage
     * @param request
     * @return
     */
    Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage, HttpServletRequest request);

    /**
     * 数据校验
     * @param space
     * @param add
     */
    void validSpace(Space space, boolean add);
    /**
     * 创建或更新空间时，根据级别自动填充额度
     * @param space
     */
    void fillSpaceBySpaceLevel(Space space);

    /**
     * 校验空间权限
     * @param loginUser
     * @param space
     */
    void checkSpaceAuth(User loginUser, Space space);
}
