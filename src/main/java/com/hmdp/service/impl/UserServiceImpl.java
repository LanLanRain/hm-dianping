package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /**
     * 发送验证码给指定的手机号
     * 此方法首先检查手机号的格式是否正确，然后生成一个随机的6位数字验证码，
     * 将验证码存储在会话中，以便后续验证
     * 注意：实际应用中，应该使用短信服务发送验证码，这里仅作示例
     *
     * @param phone   手机号码
     * @param session HTTP会话，用于存储验证码
     * @return 如果验证码发送成功，返回成功结果；否则，返回失败结果
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 检查手机号格式是否正确
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 生成6位随机数字作为验证码
        String code = RandomUtil.randomNumbers(6);
        // 将验证码存储在会话中
        session.setAttribute("code", code);
        // 记录日志，表示验证码发送成功
        log.info("发送验证码成功：{}", code);
        // 返回操作成功的结果
        return Result.ok();
    }

    /**
     * 用户登录方法
     *
     * @param loginForm 登录表单数据，包含手机号和验证码
     * @param session   HTTP会话，用于存储验证码和用户信息
     * @return 登录结果，包含成功或失败的信息
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 获取手机号
        String phone = loginForm.getPhone();
        // 检查手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 获取验证码
        String code = loginForm.getCode();
        // 从会话中获取缓存的验证码
        Object cacheCode = session.getAttribute("code");
        // 验证验证码是否正确
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }
        // 根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 如果用户不存在，则创建新用户
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        // 将用户信息存入会话
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        // 登录成功
        return Result.ok();
    }


    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return null;
    }
}
