package com.example.entity.dto;

import lombok.Data;

/**
 * WebRTC 通话信令数据。
 *
 * <p>单聊只需要收发双方与 signalData；群聊在 P2P mesh 中仍按点对点转发信令，
 * 额外字段用于让客户端识别同一个群通话上下文。</p>
 */
@Data
public class PeerConnectionDataDto {
    private String sendUserId;
    private String receiveUserId;
    private String signalType;
    private String signalData;
    private Integer messageType; // 15 视频通话，17 语音通话
    private String callId;
    private String callMode; // single / group
    private String mediaType; // audio / video
    private String groupId;
    private String groupName;
}
