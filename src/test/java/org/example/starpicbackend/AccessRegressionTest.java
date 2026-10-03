package org.example.starpicbackend;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.starpicbackend.controller.PictureController;
import org.example.starpicbackend.controller.SpaceController;
import org.example.starpicbackend.exception.BusinessException;
import org.example.starpicbackend.exception.ErrorCode;
import org.example.starpicbackend.model.dto.picture.PictureQueryRequest;
import org.example.starpicbackend.model.dto.space.SpaceQueryRequest;
import org.example.starpicbackend.model.entity.Picture;
import org.example.starpicbackend.model.entity.Space;
import org.example.starpicbackend.model.entity.User;
import org.example.starpicbackend.service.*;
import org.example.starpicbackend.service.impl.SpaceServiceImpl;
import org.example.starpicbackend.utils.QueryRequestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccessRegressionTest {
    private final PictureService pictures = mock(PictureService.class);
    private final SpaceService spaces = mock(SpaceService.class);
    private final UserService users = mock(UserService.class);
    private final PictureController controller = new PictureController();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(controller, "pictureService", pictures);
        ReflectionTestUtils.setField(controller, "spaceService", spaces);
        ReflectionTestUtils.setField(controller, "userService", users);
        when(pictures.getQueryWrapper(any())).thenReturn(new QueryWrapper<>());
        when(pictures.page(any(Page.class), any())).thenReturn(new Page<Picture>());
    }
    @Test void publicListCannotIncludePrivateOrUnreviewedPictures() {
        PictureQueryRequest query = new PictureQueryRequest(); query.setReviewStatus(0);
        controller.listPictureVOByPage(query, request);
        assertTrue(query.isNullSpaceId()); assertEquals(1, query.getReviewStatus());
        verifyNoInteractions(users);
    }
    @Test void privateOwnerCanSeePendingPictures() {
        PictureQueryRequest query = new PictureQueryRequest(); query.setSpaceId(9L); query.setReviewStatus(1);
        User owner = new User(); owner.setId(1L); Space space = new Space(); space.setUserId(1L);
        when(users.getLoginUser(request)).thenReturn(owner); when(spaces.getById(9L)).thenReturn(space);
        controller.listPictureVOByPage(query, request);
        assertNull(query.getReviewStatus()); assertFalse(query.isNullSpaceId());
        verify(spaces).checkSpaceAuth(owner, space);
    }
    @Test void cachedRouteRejectsOtherUsersSpaceBeforeQuerying() {
        PictureQueryRequest query = new PictureQueryRequest(); query.setSpaceId(9L);
        User attacker = new User(); attacker.setId(2L); Space space = new Space(); space.setUserId(1L);
        when(users.getLoginUser(request)).thenReturn(attacker); when(spaces.getById(9L)).thenReturn(space);
        doThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR)).when(spaces).checkSpaceAuth(attacker, space);
        assertThrows(BusinessException.class, () -> controller.listPictureVOByPageWithCache(query, request));
        verify(pictures, never()).page(any(Page.class), any());
    }
    @Test void anonymousCannotFetchPendingPublicPictureById() {
        Picture picture = new Picture(); picture.setId(5L); picture.setReviewStatus(0);
        when(pictures.getById(5L)).thenReturn(picture);
        when(users.getLoginUser(request)).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        assertThrows(BusinessException.class, () -> controller.getPictureVOById(5L, request));
    }
    @Test void approvedPublicDetailsDoNotRequireLogin() {
        Picture picture = new Picture(); picture.setId(5L); picture.setReviewStatus(1);
        when(pictures.getById(5L)).thenReturn(picture);
        controller.getPictureVOById(5L, request); verifyNoInteractions(users);
    }
    @Test void spaceListForcesCurrentUserInsteadOfRequestedOwner() {
        SpaceController c = new SpaceController();
        ReflectionTestUtils.setField(c,"spaceService",spaces); ReflectionTestUtils.setField(c,"userService",users);
        User owner = new User(); owner.setId(1L); when(users.getLoginUser(request)).thenReturn(owner);
        when(spaces.page(any(Page.class),any())).thenReturn(new Page<Space>());
        SpaceQueryRequest query = new SpaceQueryRequest(); query.setUserId(2L);
        c.listSpaceVOByPage(query,request); assertEquals(1L,query.getUserId());
    }
    @Test void emptySpaceQueryDoesNotUnboxNullUserId() {
        assertDoesNotThrow(() -> new SpaceServiceImpl().getQueryWrapper(new SpaceQueryRequest()));
    }
    @Test void invalidPaginationAndSortingAreRejected() {
        PictureQueryRequest query = new PictureQueryRequest(); query.setPageSize(0);
        assertThrows(BusinessException.class, () -> controller.listPictureVOByPage(query,request));
        query.setPageSize(10); query.setSortField("name desc, (select 1)");
        assertThrows(BusinessException.class, () -> QueryRequestUtils.validateSort(query,"name","createTime"));
    }
}
