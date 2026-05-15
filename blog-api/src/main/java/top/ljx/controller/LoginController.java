package top.ljx.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import top.ljx.constant.JwtConstants;
import top.ljx.entity.User;
import top.ljx.model.dto.LoginInfo;
import top.ljx.model.vo.Result;
import top.ljx.service.UserService;
import top.ljx.util.JwtUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * @Description: 前台登录
 * @Author: Naccl
 * @Date: 2020-09-02
 */
@RestController
public class LoginController {
	@Autowired
	UserService userService;

	/**
	 * 登录成功后，签发博主身份Token
	 *
	 * @param loginInfo
	 * @return
	 */
	@PostMapping("/login")
	public Result login(@RequestBody LoginInfo loginInfo) {
		User user = userService.findUserByUsernameAndPassword(loginInfo.getUsername(), loginInfo.getPassword());
		if (!"ROLE_admin".equals(user.getRole())) {
			return Result.create(403, "无权限");
		}
		user.setPassword(null);
		String jwt = JwtUtils.generateToken(JwtConstants.ADMIN_PREFIX + user.getUsername());
		Map<String, Object> map = new HashMap<>(4);
		map.put("user", user);
		map.put("token", jwt);
		return Result.ok("登录成功", map);
	}
}
