package com.example.clouddisk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.clouddisk.common.LoginUser;
import com.example.clouddisk.common.UserVO;
import com.example.clouddisk.dto.UserForm;
import com.example.clouddisk.entity.SysUser;
import com.example.clouddisk.mapper.SysUserMapper;
import com.example.clouddisk.service.SysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
public class SysUserServiceImpl implements SysUserService {

    private static final Pattern USERNAME_RE = Pattern.compile("^[A-Za-z0-9_.@-]{3,32}$");
    private static final int PWD_MIN = 6;
    private static final int PWD_MAX = 64;

    private final SysUserMapper mapper;
    private final PasswordEncoder encoder;

    @Value("${app.admin.username:admin}")
    private String managedUsername;

    public SysUserServiceImpl(SysUserMapper mapper, PasswordEncoder encoder) {
        this.mapper = mapper;
        this.encoder = encoder;
    }

    @Override
    public String managedUsername() {
        return managedUsername;
    }

    // ---------------- 初始化 ----------------

    @Override
    public void ensureAdmin(String username, String rawPassword, String displayName,
                            String role, boolean syncPassword) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(rawPassword)) {
            log.warn("管理员账号配置不完整（app.admin.username / password），跳过初始化");
            return;
        }
        String name = username.trim();
        SysUser exist = findByUsername(name);

        if (exist == null) {
            SysUser u = new SysUser();
            u.setUsername(name);
            u.setPassword(encoder.encode(rawPassword));
            u.setDisplayName(StringUtils.hasText(displayName) ? displayName.trim() : name);
            u.setRole(LoginUser.ROLE_ADMIN);
            u.setEnabled(1);
            mapper.insert(u);
            log.info("已按配置初始化管理员账号：{}", name);
            return;
        }

        boolean changed = false;
        if (syncPassword && !encoder.matches(rawPassword, exist.getPassword())) {
            exist.setPassword(encoder.encode(rawPassword));
            changed = true;
        }
        if (!LoginUser.ROLE_ADMIN.equalsIgnoreCase(exist.getRole())) {
            exist.setRole(LoginUser.ROLE_ADMIN);
            changed = true;
        }
        if (exist.getEnabled() == null || exist.getEnabled() != 1) {
            exist.setEnabled(1);
            changed = true;
        }
        if (changed) {
            mapper.updateById(exist);
            log.info("已按配置同步管理员账号：{}（密码以 application.yml 为准）", name);
        } else {
            log.info("管理员账号已存在且与配置一致：{}", name);
        }
    }

    // ---------------- 登录 ----------------

    @Override
    public SysUser authenticate(String username, String rawPassword) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(rawPassword)) {
            throw new IllegalArgumentException("请输入用户名和密码");
        }
        SysUser u = findByUsername(username.trim());
        // 用户不存在与密码错误返回同一句提示，避免暴露账号是否存在
        if (u == null || !encoder.matches(rawPassword, u.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (u.getEnabled() == null || u.getEnabled() != 1) {
            throw new IllegalArgumentException("账号已被禁用，请联系管理员");
        }

        SysUser touch = new SysUser();
        touch.setId(u.getId());
        touch.setLastLoginTime(LocalDateTime.now());
        mapper.updateById(touch);
        u.setLastLoginTime(touch.getLastLoginTime());
        return u;
    }

    @Override
    public LoginUser loadForSession(Long id) {
        if (id == null) {
            return null;
        }
        SysUser u = mapper.selectById(id);
        if (u == null || u.getEnabled() == null || u.getEnabled() != 1) {
            return null;
        }
        return toLoginUser(u);
    }

    // ---------------- 用户管理 ----------------

    @Override
    public List<UserVO> listUsers(String keyword) {
        LambdaQueryWrapper<SysUser> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(SysUser::getUsername, kw).or().like(SysUser::getDisplayName, kw));
        }
        w.orderByAsc(SysUser::getId);
        List<UserVO> out = new ArrayList<>();
        for (SysUser u : mapper.selectList(w)) {
            out.add(UserVO.of(u, managedUsername));
        }
        return out;
    }

    @Override
    public UserVO createUser(UserForm form) {
        String name = normUsername(form.getUsername());
        checkPassword(form.getPassword());
        if (findByUsername(name) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }
        SysUser u = new SysUser();
        u.setUsername(name);
        u.setPassword(encoder.encode(form.getPassword()));
        u.setDisplayName(StringUtils.hasText(form.getDisplayName()) ? form.getDisplayName().trim() : name);
        u.setRole(normRole(form.getRole()));
        u.setEnabled(form.getEnabled() == null || form.getEnabled() ? 1 : 0);
        mapper.insert(u);
        return UserVO.of(u, managedUsername);
    }

    @Override
    public UserVO updateUser(Long id, UserForm form, LoginUser operator) {
        SysUser u = mustGet(id);
        boolean managed = isManaged(u);

        // 用户名：托管账号不允许改名，其它账号要求唯一
        if (StringUtils.hasText(form.getUsername())) {
            String name = normUsername(form.getUsername());
            if (!name.equals(u.getUsername())) {
                if (managed) {
                    throw new IllegalArgumentException("该账号由 application.yml 托管，不能修改用户名");
                }
                if (findByUsername(name) != null) {
                    throw new IllegalArgumentException("用户名已存在");
                }
                u.setUsername(name);
            }
        }

        if (form.getDisplayName() != null) {
            String dn = form.getDisplayName().trim();
            u.setDisplayName(dn.isEmpty() ? u.getUsername() : dn);
        }

        // 角色
        if (StringUtils.hasText(form.getRole())) {
            String role = normRole(form.getRole());
            if (!role.equalsIgnoreCase(u.getRole())) {
                if (managed) {
                    throw new IllegalArgumentException("该账号由 application.yml 托管，不能修改角色");
                }
                if (LoginUser.ROLE_ADMIN.equalsIgnoreCase(u.getRole())
                        && countEnabledAdmins(u.getId()) == 0) {
                    throw new IllegalArgumentException("系统至少需要保留一个启用的管理员");
                }
                u.setRole(role);
            }
        }

        // 启用 / 禁用
        if (form.getEnabled() != null) {
            int enabled = form.getEnabled() ? 1 : 0;
            if (enabled == 0 && (u.getEnabled() == null || u.getEnabled() == 1)) {
                if (operator != null && id.equals(operator.getId())) {
                    throw new IllegalArgumentException("不能禁用当前登录的账号");
                }
                if (LoginUser.ROLE_ADMIN.equalsIgnoreCase(u.getRole()) && countEnabledAdmins(u.getId()) == 0) {
                    throw new IllegalArgumentException("系统至少需要保留一个启用的管理员");
                }
            }
            u.setEnabled(enabled);
        }

        // 密码：附带就一起改（托管账号除外）
        if (StringUtils.hasText(form.getPassword())) {
            if (managed) {
                throw new IllegalArgumentException("该账号密码由 application.yml 托管，请修改 app.admin.password");
            }
            checkPassword(form.getPassword());
            u.setPassword(encoder.encode(form.getPassword()));
        }

        mapper.updateById(u);
        return UserVO.of(u, managedUsername);
    }

    @Override
    public void resetPassword(Long id, String newPassword) {
        SysUser u = mustGet(id);
        if (isManaged(u)) {
            throw new IllegalArgumentException("该账号密码由 application.yml 托管，请修改 app.admin.password");
        }
        checkPassword(newPassword);
        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setPassword(encoder.encode(newPassword));
        mapper.updateById(upd);
    }

    @Override
    public void changeOwnPassword(LoginUser me, String oldPassword, String newPassword) {
        if (me == null) {
            throw new IllegalArgumentException("未登录");
        }
        SysUser u = mustGet(me.getId());
        if (isManaged(u)) {
            throw new IllegalArgumentException("管理员密码由 application.yml 托管，请修改 app.admin.password");
        }
        if (!StringUtils.hasText(oldPassword) || !encoder.matches(oldPassword, u.getPassword())) {
            throw new IllegalArgumentException("原密码不正确");
        }
        checkPassword(newPassword);
        SysUser upd = new SysUser();
        upd.setId(u.getId());
        upd.setPassword(encoder.encode(newPassword));
        mapper.updateById(upd);
    }

    @Override
    public void deleteUser(Long id, LoginUser operator) {
        SysUser u = mustGet(id);
        if (operator != null && id.equals(operator.getId())) {
            throw new IllegalArgumentException("不能删除当前登录的账号");
        }
        if (isManaged(u)) {
            throw new IllegalArgumentException("该账号由 application.yml 托管，不能删除");
        }
        if (LoginUser.ROLE_ADMIN.equalsIgnoreCase(u.getRole())
                && (u.getEnabled() != null && u.getEnabled() == 1)
                && countEnabledAdmins(id) == 0) {
            throw new IllegalArgumentException("系统至少需要保留一个启用的管理员");
        }
        mapper.deleteById(id);
    }

    // ---------------- 内部工具 ----------------

    private SysUser findByUsername(String username) {
        LambdaQueryWrapper<SysUser> w = new LambdaQueryWrapper<>();
        w.eq(SysUser::getUsername, username).last("LIMIT 1");
        return mapper.selectOne(w);
    }

    private SysUser mustGet(Long id) {
        SysUser u = id == null ? null : mapper.selectById(id);
        if (u == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return u;
    }

    private boolean isManaged(SysUser u) {
        return managedUsername != null && managedUsername.equalsIgnoreCase(u.getUsername());
    }

    /** 除 excludeId 之外，还有几个启用的管理员 */
    private long countEnabledAdmins(Long excludeId) {
        LambdaQueryWrapper<SysUser> w = new LambdaQueryWrapper<>();
        w.eq(SysUser::getRole, LoginUser.ROLE_ADMIN).eq(SysUser::getEnabled, 1);
        if (excludeId != null) {
            w.ne(SysUser::getId, excludeId);
        }
        Long c = mapper.selectCount(w);
        return c == null ? 0L : c;
    }

    private String normUsername(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("请输入用户名");
        }
        String name = raw.trim();
        if (!USERNAME_RE.matcher(name).matches()) {
            throw new IllegalArgumentException("用户名需 3~32 位，仅支持字母、数字与 _ . @ -");
        }
        return name;
    }

    private void checkPassword(String pwd) {
        if (!StringUtils.hasText(pwd) || pwd.length() < PWD_MIN) {
            throw new IllegalArgumentException("密码至少 " + PWD_MIN + " 位");
        }
        if (pwd.length() > PWD_MAX) {
            throw new IllegalArgumentException("密码最长 " + PWD_MAX + " 位");
        }
    }

    private String normRole(String raw) {
        if (!StringUtils.hasText(raw)) {
            return LoginUser.ROLE_USER;
        }
        String role = raw.trim().toUpperCase();
        if (!LoginUser.ROLE_ADMIN.equals(role) && !LoginUser.ROLE_USER.equals(role)) {
            throw new IllegalArgumentException("角色只能是 ADMIN 或 USER");
        }
        return role;
    }

    private LoginUser toLoginUser(SysUser u) {
        String dn = (u.getDisplayName() == null || u.getDisplayName().trim().isEmpty())
                ? u.getUsername() : u.getDisplayName();
        return new LoginUser(u.getId(), u.getUsername(), dn, u.getRole());
    }
}
