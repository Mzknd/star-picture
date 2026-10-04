package org.example.starpicbackend;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.starpicbackend.constant.UserConstant;
import org.example.starpicbackend.exception.BusinessException;
import org.example.starpicbackend.mapper.UserMapper;
import org.example.starpicbackend.model.entity.User;
import org.example.starpicbackend.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordCompatibilityTest {
    private final UserMapper mapper = mock(UserMapper.class);
    private final UserServiceImpl service = new UserServiceImpl();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final String password = "legacy-password";

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test void newHashesUseRandomSaltAndRejectBcryptTruncation() {
        String first = service.getEncryptPassword(password);
        String second = service.getEncryptPassword(password);
        assertNotEquals(first, second);
        assertTrue(encoder.matches(password, first));
        assertTrue(encoder.matches("a".repeat(72), service.getEncryptPassword("a".repeat(72))));
        assertThrows(BusinessException.class, () -> service.getEncryptPassword("a".repeat(73)));
        assertThrows(BusinessException.class, () -> service.getEncryptPassword("密".repeat(25)));
    }

    @Test void newRegistrationStoresBcryptHash() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(User.class))).thenAnswer(call -> {
            User inserted = call.getArgument(0);
            assertTrue(encoder.matches(password, inserted.getUserPassword()));
            inserted.setId(7L);
            return 1;
        });
        assertEquals(7L, service.userRegister("staruser", password, password));
        verify(mapper).insert(any(User.class));
    }

    @Test void legacyLoginUpgradesHashAndRotatesSession() {
        User user = user(legacyHash());
        when(mapper.selectOne(any())).thenReturn(user);
        when(mapper.update(ArgumentMatchers.<User>isNull(), any(Wrapper.class))).thenReturn(1);
        String oldSessionId = request.getSession().getId();
        service.userLogin("staruser", password, request);
        assertTrue(encoder.matches(password, user.getUserPassword()));
        assertNotEquals(oldSessionId, request.getSession().getId());
        assertSame(user, request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE));
        verify(mapper).update(ArgumentMatchers.<User>isNull(), any(Wrapper.class));
    }

    @Test void incorrectLegacyPasswordDoesNotUpgradeOrCreateLoginState() {
        when(mapper.selectOne(any())).thenReturn(user(legacyHash()));
        assertThrows(BusinessException.class, () -> service.userLogin("staruser", "wrong-password", request));
        verify(mapper, never()).update(ArgumentMatchers.<User>isNull(), any(Wrapper.class));
        assertNull(request.getSession(false));
    }

    @Test void bcryptAccountLogsInWithoutWritingPassword() {
        User user = user(encoder.encode(password));
        when(mapper.selectOne(any())).thenReturn(user);
        service.userLogin("staruser", password, request);
        verify(mapper, never()).update(ArgumentMatchers.<User>isNull(), any(Wrapper.class));
        assertNotNull(request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE));
    }

    @Test void concurrentPasswordChangeCannotBeOverwrittenByLegacyUpgrade() {
        when(mapper.selectOne(any())).thenReturn(user(legacyHash()));
        when(mapper.update(ArgumentMatchers.<User>isNull(), any(Wrapper.class))).thenReturn(0);
        when(mapper.selectById(7L)).thenReturn(user(encoder.encode("replacement-password")));
        assertThrows(BusinessException.class, () -> service.userLogin("staruser", password, request));
        assertNull(request.getSession(false));
    }

    @Test void concurrentSuccessfulUpgradeStillAllowsSamePassword() {
        when(mapper.selectOne(any())).thenReturn(user(legacyHash()));
        when(mapper.update(ArgumentMatchers.<User>isNull(), any(Wrapper.class))).thenReturn(0);
        when(mapper.selectById(7L)).thenReturn(user(encoder.encode(password)));
        assertDoesNotThrow(() -> service.userLogin("staruser", password, request));
    }

    private String legacyHash() {
        return DigestUtils.md5DigestAsHex(("Sakusei" + password).getBytes(StandardCharsets.UTF_8));
    }

    private User user(String hash) {
        User user = new User();
        user.setId(7L);
        user.setUserAccount("staruser");
        user.setUserPassword(hash);
        return user;
    }
}