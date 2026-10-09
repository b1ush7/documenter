package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.documenter.dto.LoginByCodeDTO;
import com.documenter.dto.RegisterByCodeDTO;
import com.documenter.dto.UserLoginDTO;
import com.documenter.dto.UserRegisterDTO;
import com.documenter.entity.User;
import com.documenter.enums.VerificationScene;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.UserMapper;
import com.documenter.service.UserService;
import com.documenter.service.VerificationCodeService;
import com.documenter.util.BcryptUtil;
import com.documenter.util.JwtUtil;
import com.documenter.vo.TokenPairVO;
import com.documenter.vo.UserInfoVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final String STATUS_ENABLED = "ENABLED";
    private static final String ROLE_USER = "USER";

    private final JwtUtil jwtUtil;
    private final VerificationCodeService verificationCodeService;

    /**
     * verificationCodeService 用 @Lazy 注入：验证码服务在发送前需要反向查询用户
     * （判断手机号是否已注册），与这里构成循环依赖。延迟到首次调用再解析即可打破环。
     */
    public UserServiceImpl(JwtUtil jwtUtil, @Lazy VerificationCodeService verificationCodeService) {
        this.jwtUtil = jwtUtil;
        this.verificationCodeService = verificationCodeService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserInfoVO register(UserRegisterDTO dto) {
        return createUser(dto.getUsername(), normalizePhone(dto.getPhone()), dto.getPassword());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserInfoVO registerByCode(RegisterByCodeDTO dto) {
        String phone = dto.getPhone().trim();
        // 先消费验证码：验证失败直接中断，不会创建用户
        verificationCodeService.consumeCode(phone, VerificationScene.REGISTER, dto.getCode());
        return createUser(dto.getUsername(), phone, dto.getPassword());
    }

    @Override
    public TokenPairVO login(UserLoginDTO dto) {
        String account = dto.getAccount() == null ? null : dto.getAccount().trim();
        if (account == null || account.isEmpty()) {
            throw new BusinessException(400, "账号不能为空");
        }

        User user = PHONE_PATTERN.matcher(account).matches() ? findByPhone(account) : findByUsername(account);
        // 账号不存在与密码错误返回同一种提示，避免被用来枚举已注册账号
        if (user == null || !BcryptUtil.check(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(401, "账号或密码错误");
        }
        return issueTokens(user);
    }

    @Override
    public TokenPairVO loginByCode(LoginByCodeDTO dto) {
        String phone = dto.getPhone().trim();
        // 消费验证码成功即证明手机号归属，无需密码
        verificationCodeService.consumeCode(phone, VerificationScene.LOGIN, dto.getCode());

        User user = findByPhone(phone);
        if (user == null) {
            // 极少数情况：验证码发送后账号被删除
            throw new BusinessException(401, "账号不存在，请重新注册");
        }
        return issueTokens(user);
    }

    @Override
    public TokenPairVO refresh(String refreshToken) {
        // 轮换由 JwtUtil 在 Redis 内原子完成，旧 Refresh Token 立即失效
        return TokenPairVO.from(jwtUtil.refreshTokenPair(refreshToken));
    }

    @Override
    public UserInfoVO currentUser(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(401, "用户不存在或已被删除");
        }
        return UserInfoVO.from(user);
    }

    @Override
    public User findByPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        return baseMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getPhone, phone.trim()));
    }

    /** 注册与验证码注册共用的创建逻辑。 */
    private UserInfoVO createUser(String rawUsername, String phone, String rawPassword) {
        String username = rawUsername == null ? null : rawUsername.trim();

        if (existsByUsername(username)) {
            throw new BusinessException(400, "用户名已被占用");
        }
        if (phone != null && existsByPhone(phone)) {
            throw new BusinessException(400, "手机号已被注册");
        }

        User user = new User();
        user.setUsername(username);
        user.setPhone(phone);
        // 只保存 BCrypt 哈希，禁止落明文（TODO 3.1）
        user.setPassword(BcryptUtil.encode(rawPassword));
        user.setRole(ROLE_USER);
        user.setStatus(STATUS_ENABLED);

        try {
            save(user);
        } catch (DuplicateKeyException e) {
            // 并发注册同名账号时先查后插会失效，靠数据库唯一索引兜底
            log.warn("注册命中唯一索引冲突, username={}", username);
            throw new BusinessException(400, "用户名或手机号已被占用");
        }
        if (user.getId() == null) {
            // 自增主键未回填说明插入结果异常，宁可报错也不要返回一个没有 ID 的用户
            throw new BusinessException(500, "注册失败，请稍后重试");
        }
        return UserInfoVO.from(user);
    }

    /** 登录与验证码登录共用的签发逻辑。 */
    private TokenPairVO issueTokens(User user) {
        if (!user.isEnabled()) {
            // 禁用用户不允许登录（TODO 3.1、3.2）
            throw new BusinessException(403, "账号已被禁用，请联系管理员");
        }
        TokenPairVO tokens = TokenPairVO.from(jwtUtil.createTokenPair(String.valueOf(user.getId())));

        // 记录最近登录时间，失败不影响登录结果
        try {
            User update = new User();
            update.setId(user.getId());
            update.setLastLoginTime(LocalDateTime.now());
            updateById(update);
        } catch (RuntimeException e) {
            log.warn("更新最近登录时间失败, userId={}", user.getId(), e);
        }
        return tokens;
    }

    private boolean existsByUsername(String username) {
        return baseMapper.exists(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    private boolean existsByPhone(String phone) {
        return baseMapper.exists(Wrappers.<User>lambdaQuery().eq(User::getPhone, phone));
    }

    private User findByUsername(String username) {
        return baseMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    private static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = phone.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
