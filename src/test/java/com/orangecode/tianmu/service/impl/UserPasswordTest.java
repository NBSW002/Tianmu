package com.orangecode.tianmu.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.orangecode.tianmu.application.PasswordHashes;
import com.orangecode.tianmu.constants.UserConstant;
import com.orangecode.tianmu.exception.BusinessException;
import com.orangecode.tianmu.mapper.UserMapper;
import com.orangecode.tianmu.model.dto.user.LoginPasswordRequest;
import com.orangecode.tianmu.model.dto.user.RegisterRequest;
import com.orangecode.tianmu.model.entity.User;
import com.orangecode.tianmu.model.entity.UserStats;
import com.orangecode.tianmu.service.UserStatsService;
import com.orangecode.tianmu.utils.JwtUtil;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.DigestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserPasswordTest {
    private UserServiceImpl service;
    private UserMapper mapper;
    private User user;
    private ValueOperations<String,String> values;
    private MockedStatic<JwtUtil> jwt;

    @BeforeEach void setup() {
        service = spy(new UserServiceImpl());
        mapper = mock(UserMapper.class);
        var redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var stats = mock(UserStatsService.class);
        when(stats.getById(1L)).thenReturn(new UserStats());
        when(stats.save(any(UserStats.class))).thenReturn(true);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "userStatsService", stats);
        user = new User(); user.setUserId(1L);
        doReturn(user).when(service).getOne(any(Wrapper.class));
        jwt = mockStatic(JwtUtil.class);
        jwt.when(() -> JwtUtil.generate(anyString())).thenReturn("session-token");
    }

    @AfterEach void close() { jwt.close(); }

    private String legacy(String password) {
        return DigestUtils.md5DigestAsHex((UserConstant.PASSWORD_SALT + password).getBytes());
    }
    private LoginPasswordRequest input(String password) {
        var input = new LoginPasswordRequest(); input.setAccount("user@example.com"); input.setPassword(password); return input;
    }

    @Test void successfulLegacyLoginUpgradesBeforeIssuingSession() {
        String old = legacy("旧密码"); user.setPassword(old);
        when(mapper.upgradePassword(eq(1L),eq(old),anyString())).thenReturn(1);
        assertEquals("session-token", service.loginPassword(input("旧密码"), null).getToken());
        verify(mapper).upgradePassword(eq(1L),eq(old),argThat(hash -> !hash.equals(old) && PasswordHashes.matches("旧密码",hash)));
        verify(values).set(eq("1"),eq("session-token"),anyLong(),any());
    }

    @Test void modernLoginDoesNotRewriteHash() {
        user.setPassword(PasswordHashes.encode("password"));
        assertEquals("session-token", service.loginPassword(input("password"), null).getToken());
        verify(mapper,never()).upgradePassword(anyLong(),anyString(),anyString());
    }

    @Test void wrongOrMissingPasswordCannotUpgradeOrIssueSession() {
        user.setPassword(legacy("correct"));
        assertThrows(BusinessException.class, () -> service.loginPassword(input("wrong"),null));
        assertThrows(BusinessException.class, () -> service.loginPassword(input(null),null));
        verify(mapper,never()).upgradePassword(anyLong(),anyString(),anyString());
        verifyNoInteractions(values); jwt.verifyNoInteractions();
    }

    @Test void concurrentSuccessfulUpgradeStillAllowsLogin() {
        user.setPassword(legacy("correct"));
        var current = new User(); current.setUserId(1L); current.setPassword(PasswordHashes.encode("correct"));
        doReturn(current).when(service).getById(1L);
        assertEquals("session-token", service.loginPassword(input("correct"),null).getToken());
        verify(mapper).upgradePassword(eq(1L),eq(user.getPassword()),anyString());
    }

    @Test void concurrentPasswordChangeRejectsOldPasswordWithoutOverwritingIt() {
        user.setPassword(legacy("old"));
        var current = new User(); current.setUserId(1L); current.setPassword(PasswordHashes.encode("new"));
        doReturn(current).when(service).getById(1L);
        assertThrows(BusinessException.class, () -> service.loginPassword(input("old"),null));
        verify(mapper,times(1)).upgradePassword(eq(1L),eq(user.getPassword()),anyString());
        verifyNoInteractions(values); jwt.verifyNoInteractions();
    }

    @Test void registrationPersistsNewHash() {
        when(values.get("user@example.com")).thenReturn("123456");
        doReturn(false).when(service).exists(any(Wrapper.class));
        doReturn(true).when(service).save(any(User.class));
        var input = new RegisterRequest(); input.setAccount("user@example.com"); input.setPassword("密码");
        input.setCode("123456"); input.setNickname("name");
        service.register(input,null);
        verify(service).save(argThat((User saved) -> !PasswordHashes.legacy(saved.getPassword()) && PasswordHashes.matches("密码",saved.getPassword())));
    }

    @Test void saltIsRandomAndLongUnicodePasswordsAreNotTruncated() {
        String password = "密码😀".repeat(30);
        String first = PasswordHashes.encode(password), second = PasswordHashes.encode(password);
        assertNotEquals(first,second);
        assertTrue(PasswordHashes.matches(password,first));
        assertFalse(PasswordHashes.matches(password+"x",first));
        assertTrue(first.length() <= 128);
    }

    @Test void malformedAndUnknownFormatsFailClosed() {
        for (String hash : new String[]{"", "plaintext", "pbkdf2-sha256$999999999$bad$bad", "pbkdf2-sha256$600000$bad$bad", "pbkdf2-sha256$600000$!$!", "0".repeat(31)}) {
            assertFalse(PasswordHashes.matches("password",hash));
        }
        assertFalse(PasswordHashes.matches("password",null));
    }
}
