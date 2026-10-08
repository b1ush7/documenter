package com.documenter.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.documenter.entity.User;
import com.documenter.mapper.UserMapper;
import com.documenter.service.UserService;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
}
