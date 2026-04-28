package com.example.mapper;

import com.example.entity.pojo.ChatSessionUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatSessionUserMapper {

    Integer insertOrUpdate(ChatSessionUser chatSessionUser);

    Integer insertOrUpdateBatch(@Param("chatSessionUsers") List<ChatSessionUser> chatSessionUsers);

    Integer updateName(@Param("contactName") String contactName, @Param("contactId") String contactId);

    Integer deleteBatch(@Param("userIds") List<String> userIds, @Param("contactId") String contactId);

    /** 查询所有把 contactId 当作联系人的用户 ID（用于改名时向好友推送通知） */
    List<String> selectContactUserIds(@Param("contactId") String contactId);

    List<ChatSessionUser> selectAll(String userId);

}
