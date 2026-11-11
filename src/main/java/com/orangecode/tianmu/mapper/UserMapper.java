package com.orangecode.tianmu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orangecode.tianmu.model.entity.User;
import com.orangecode.tianmu.model.vo.user.LoginResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @org.apache.ibatis.annotations.Update("UPDATE `user` SET password=#{replacement} WHERE user_id=#{id} AND CAST(password AS BINARY)=CAST(#{expected} AS BINARY)")
    int upgradePassword(@org.apache.ibatis.annotations.Param("id") long id,
                        @org.apache.ibatis.annotations.Param("expected") String expected,
                        @org.apache.ibatis.annotations.Param("replacement") String replacement);


    LoginResponse getUserInfo(Long userId);
}
