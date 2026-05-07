package com.example.websocket;

import com.alibaba.fastjson2.JSON;
import com.example.entity.dto.PeerConnectionDataDto;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class WebSocketMessageService {

    @Autowired
    private ChannelContextUtils channelContextUtils;
    private final Map<String, Map<String, Object>> activeGroupCallMap = new ConcurrentHashMap<>();

    /**
     * 处理WebSocket消息的核心方法
     *
     * @param ctx WebSocket上下文
     * @param data 接收到的消息数据
     */
    public void handleMessage(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        try {
            Channel channel = ctx.channel();
            // 获取发送者信息
            String currentUserId = channelContextUtils.getUserId(channel);
            if (currentUserId == null) {
                sendErrorResponse(ctx, "用户未登录或连接无效");
                return;
            }

            // 设置真实的发送者ID（防止伪造）
            data.setSendUserId(currentUserId);

            // 验证消息数据
            if (!validateMessageData(ctx, data)) {
                return;
            }

            // 记录消息日志
            logMessage(data);

            // 根据消息类型处理
            String signalType = data.getSignalType();
            switch (signalType.toLowerCase()) {
                case "offer":
                    handleOfferMessage(ctx, data);
                    break;

                case "answer":
                    handleAnswerMessage(ctx, data);
                    break;

                case "candidate":
                    handleCandidateMessage(ctx, data);
                    break;

                case "end_call":
                    handleEndCallMessage(ctx, data);
                    break;

                case "reject_call":
                    handleRejectCallMessage(ctx, data);
                    break;

                case "group_invite":
                    rememberGroupCall(data);
                    handleGroupCallMessage(ctx, data);
                    break;

                case "join_call":
                    // 群通话邀请/加入通知仍按目标成员逐个点对点透传。
                    syncGroupCallParticipantState(data, "joined");
                    handleGroupCallMessage(ctx, data);
                    break;

                case "member_status":
                    syncGroupCallParticipantState(data, null);
                    handleGroupCallMessage(ctx, data);
                    break;

                case "leave_call":
                    // 群通话中单个成员离开，仍按点对点信令透传给其他成员。
                    syncGroupCallParticipantState(data, "left");
                    handleLeaveCallMessage(ctx, data);
                    break;

                case "close_group_call":
                    handleCloseGroupCall(data);
                    break;

                case "query_group_call":
                    handleQueryGroupCall(ctx, data);
                    break;

                case "camera_toggle":
                    // 摄像头开关通知，直接透传给对方，让对方更新占位符显示状态
                    handleCameraToggle(ctx, data);
                    break;

                case "heartbeat":
                case "ping":
                    handleHeartbeatMessage(ctx, data);
                    break;

                case "call_request":
                    handleCallRequest(ctx, data);
                    break;

                case "call_response":
                    handleCallResponse(ctx, data);
                    break;

                default:
                    handleUnknownMessage(ctx, data);
            }

        } catch (Exception e) {
            log.error("处理WebSocket消息时发生异常", e);
            sendErrorResponse(ctx, "消息处理失败: " + e.getMessage());
        }
    }

    /**
     * 处理Offer消息（发起通话）
     */
    private void handleOfferMessage(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        log.info("处理Offer消息: {} -> {}", data.getSendUserId(), data.getReceiveUserId());

        // 检查目标用户是否在线
        if (!isUserOnline(data.getReceiveUserId())) {
            sendErrorResponse(ctx, "目标用户不在线: " + data.getReceiveUserId());
            return;
        }

        // 转发Offer给目标用户
        boolean success = forwardMessageToUser(data);
        if (success) {
            // 向发送者确认消息已发送
            sendSuccessResponse(ctx, "offer", "呼叫请求已发送给 " + data.getReceiveUserId());
        } else {
            sendErrorResponse(ctx, "发送呼叫请求失败");
        }
    }

    /**
     * 处理Answer消息（应答通话）
     */
    private void handleAnswerMessage(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        log.info("处理Answer消息: {} -> {}", data.getSendUserId(), data.getReceiveUserId());

        // 转发Answer给发起方
        boolean success = forwardMessageToUser(data);
        if (success) {
            sendSuccessResponse(ctx, "answer", "通话应答已发送");
        } else {
            sendErrorResponse(ctx, "发送通话应答失败");
        }
    }

    /**
     * 处理ICE Candidate消息
     */
    private void handleCandidateMessage(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        log.debug("处理Candidate消息: {} -> {}", data.getSendUserId(), data.getReceiveUserId());

        // 直接转发ICE候选信息
        boolean success = forwardMessageToUser(data);
        if (!success) {
            log.warn("转发ICE候选信息失败: {} -> {}", data.getSendUserId(), data.getReceiveUserId());
        }
    }

    /**
     * 处理结束通话消息
     */
    private void handleEndCallMessage(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        log.info("处理EndCall消息: {} -> {}", data.getSendUserId(), data.getReceiveUserId());

        // 转发结束通话消息
        boolean success = forwardMessageToUser(data);
        if (success) {
            sendSuccessResponse(ctx, "end_call", "通话结束消息已发送");
        }
    }

    /**
     * 处理拒绝通话消息
     */
    private void handleRejectCallMessage(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        log.info("处理RejectCall消息: {} -> {}", data.getSendUserId(), data.getReceiveUserId());

        // 转发拒绝通话消息
        boolean success = forwardMessageToUser(data);
        if (success) {
            sendSuccessResponse(ctx, "reject_call", "通话拒绝消息已发送");
        }
    }

    /**
     * 处理群通话邀请/加入通知
     */
    private void handleGroupCallMessage(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        log.info("处理群通话信令: {}, {} -> {}", data.getSignalType(), data.getSendUserId(), data.getReceiveUserId());
        forwardMessageToUser(data);
    }

    /**
     * 记录当前群通话上下文，供群聊顶部“加入当前群通话”入口查询。
     */
    private void rememberGroupCall(PeerConnectionDataDto data) {
        if (data.getGroupId() == null) {
            return;
        }
        Map<String, Object> callInfo = parseSignalData(data.getSignalData());
        callInfo.put("active", true);
        callInfo.put("callId", data.getCallId());
        callInfo.put("groupId", data.getGroupId());
        callInfo.put("groupName", data.getGroupName());
        callInfo.put("mediaType", data.getMediaType());
        callInfo.put("inviterId", data.getSendUserId());
        markParticipantStatus(callInfo, data.getSendUserId(), "joined", String.valueOf(callInfo.get("inviterName")));
        activeGroupCallMap.put(data.getGroupId(), callInfo);
        notifyGroupCallState(data.getGroupId(), callInfo, true);
    }

    /**
     * 同步群通话成员状态。成员全部离开后清理内存态，避免后续误加入旧通话。
     */
    private void updateGroupCallParticipant(PeerConnectionDataDto data, String status) {
        Map<String, Object> callInfo = activeGroupCallMap.get(data.getGroupId());
        if (callInfo == null) {
            return;
        }
        Map<String, Object> signalData = parseSignalData(data.getSignalData());
        String participantId = signalData.get("userId") == null
                ? data.getSendUserId()
                : String.valueOf(signalData.get("userId"));
        String nextStatus = status == null ? String.valueOf(signalData.get("status")) : status;
        if (nextStatus == null || "null".equals(nextStatus)) {
            return;
        }
        String participantName = signalData.get("name") == null ? participantId : String.valueOf(signalData.get("name"));
        markParticipantStatus(callInfo, participantId, nextStatus, participantName);
        if ("left".equals(status) && getActiveParticipantCount(callInfo) == 0) {
            // 最后一名在线成员离开后，主动广播 inactive，确保聊天页按钮立即恢复状态。
            notifyGroupCallState(data.getGroupId(), callInfo, false);
            activeGroupCallMap.remove(data.getGroupId());
            return;
        }
        notifyGroupCallState(data.getGroupId(), callInfo, true);
        // 仅在群通话没有任何在线成员时才清理，避免只剩 1 人在线时顶部“加入通话”入口提前消失。
        if ("left".equals(status) && getActiveParticipantCount(callInfo) == 0) {
            activeGroupCallMap.remove(data.getGroupId());
        }
    }

    /**
     * 查询指定群当前是否存在可加入的群通话。
     */
    /**
     * 新的群通话成员状态同步入口。
     * 不再复用上面的历史实现，避免旧坏行把通话上下文提前清掉。
     */
    private void syncGroupCallParticipantState(PeerConnectionDataDto data, String status) {
        Map<String, Object> callInfo = activeGroupCallMap.get(data.getGroupId());
        if (callInfo == null) {
            return;
        }

        Map<String, Object> signalData = parseSignalData(data.getSignalData());
        String participantId = signalData.get("userId") == null
                ? data.getSendUserId()
                : String.valueOf(signalData.get("userId"));
        String nextStatus = status == null ? String.valueOf(signalData.get("status")) : status;
        if (nextStatus == null || "null".equals(nextStatus)) {
            return;
        }

        String participantName = signalData.get("name") == null
                ? participantId
                : String.valueOf(signalData.get("name"));
        markParticipantStatus(callInfo, participantId, nextStatus, participantName);

        if ("left".equals(status) && getActiveParticipantCount(callInfo) == 0) {
            notifyGroupCallState(data.getGroupId(), callInfo, false);
            activeGroupCallMap.remove(data.getGroupId());
            return;
        }

        notifyGroupCallState(data.getGroupId(), callInfo, true);
    }

    /**
     * 最后一名在线成员关闭通话窗口时，前端会显式通知服务端清理群通话上下文。
     * 这条链路不依赖点对点转发，避免“最后一人退出时没有接收方”导致房间残留。
     */
    private void handleCloseGroupCall(PeerConnectionDataDto data) {
        Map<String, Object> callInfo = activeGroupCallMap.remove(data.getGroupId());
        if (callInfo == null) {
            return;
        }
        notifyGroupCallState(data.getGroupId(), callInfo, false);
    }

    private void handleQueryGroupCall(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        Map<String, Object> callInfo = activeGroupCallMap.get(data.getGroupId());
        Map<String, Object> responseData = new HashMap<>();
        if (callInfo == null) {
            responseData.put("active", false);
        } else {
            responseData.putAll(callInfo);
            responseData.put("active", true);
        }

        PeerConnectionDataDto response = new PeerConnectionDataDto();
        response.setSendUserId(data.getSendUserId());
        response.setReceiveUserId(data.getSendUserId());
        response.setSignalType("group_call_state");
        response.setSignalData(JSON.toJSONString(responseData));
        response.setCallMode("group");
        response.setGroupId(data.getGroupId());
        response.setGroupName(data.getGroupName());
        response.setCallId(callInfo == null ? null : String.valueOf(callInfo.get("callId")));
        String mediaType = callInfo == null ? data.getMediaType() : String.valueOf(callInfo.get("mediaType"));
        response.setMediaType(mediaType);
        response.setMessageType("video".equals(mediaType) ? 15 : 17);
        channelContextUtils.sendMessageToUser(data.getSendUserId(), JSON.toJSONString(response));
    }

    private Map<String, Object> parseSignalData(String signalData) {
        if (signalData == null || signalData.trim().isEmpty()) {
            return new HashMap<>();
        }
        try {
            return JSON.parseObject(signalData, Map.class);
        } catch (Exception e) {
            log.warn("解析群通话状态失败", e);
            return new HashMap<>();
        }
    }

    private void markParticipantStatus(Map<String, Object> callInfo, String userId, String status, String name) {
        if (userId == null) {
            return;
        }
        List<Map<String, Object>> participants = getParticipants(callInfo);
        for (Map<String, Object> participant : participants) {
            Object id = participant.get("id");
            if (id == null) {
                id = participant.get("userId");
            }
            if (userId.equals(String.valueOf(id))) {
                participant.put("status", status);
                if (name != null && !"null".equals(name)) {
                    participant.put("name", name);
                }
                return;
            }
        }

        Map<String, Object> participant = new HashMap<>();
        participant.put("id", userId);
        participant.put("name", name == null || "null".equals(name) ? userId : name);
        participant.put("status", status);
        participants.add(participant);
    }

    private List<Map<String, Object>> getParticipants(Map<String, Object> callInfo) {
        Object participantsObj = callInfo.get("participants");
        if (participantsObj instanceof List) {
            return (List<Map<String, Object>>) participantsObj;
        }
        List<Map<String, Object>> participants = new ArrayList<>();
        callInfo.put("participants", participants);
        return participants;
    }

    private int getActiveParticipantCount(Map<String, Object> callInfo) {
        int count = 0;
        for (Map<String, Object> participant : getParticipants(callInfo)) {
            Object status = participant.get("status");
            if ("self".equals(status) || "joined".equals(status) || "connected".equals(status)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 群通话状态发生变化后，把最新状态主动推送给已知成员。
     * 主聊天页依赖这个信令实时刷新“发起/加入通话”按钮状态。
     */
    private void notifyGroupCallState(String groupId, Map<String, Object> callInfo, boolean active) {
        if (groupId == null || callInfo == null) {
            return;
        }

        List<Map<String, Object>> participants = getParticipants(callInfo);
        if (participants.isEmpty()) {
            return;
        }

        Map<String, Object> responseData = new HashMap<>();
        if (active) {
            responseData.putAll(callInfo);
        }
        responseData.put("active", active);

        String mediaType = String.valueOf(callInfo.getOrDefault("mediaType", "audio"));
        String inviterId = String.valueOf(callInfo.getOrDefault("inviterId", ""));
        String groupName = String.valueOf(callInfo.getOrDefault("groupName", ""));
        String callId = String.valueOf(callInfo.getOrDefault("callId", ""));

        for (Map<String, Object> participant : participants) {
            Object participantId = participant.get("id");
            if (participantId == null) {
                participantId = participant.get("userId");
            }
            if (participantId == null) {
                continue;
            }

            String receiveUserId = String.valueOf(participantId);
            PeerConnectionDataDto response = new PeerConnectionDataDto();
            response.setSendUserId(inviterId);
            response.setReceiveUserId(receiveUserId);
            response.setSignalType("group_call_state");
            response.setSignalData(JSON.toJSONString(responseData));
            response.setCallMode("group");
            response.setGroupId(groupId);
            response.setGroupName(groupName);
            response.setCallId(callId);
            response.setMediaType(mediaType);
            response.setMessageType("video".equals(mediaType) ? 15 : 17);
            channelContextUtils.sendMessageToUser(receiveUserId, JSON.toJSONString(response));
        }
    }

    /**
     * 处理群通话成员离开消息
     */
    private void handleLeaveCallMessage(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        log.info("处理LeaveCall消息: {} -> {}", data.getSendUserId(), data.getReceiveUserId());
        forwardMessageToUser(data);
    }

    /**
     * 处理心跳消息
     */
    private void handleHeartbeatMessage(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        log.debug("处理心跳消息: {}", data.getSendUserId());

        // 回复心跳
        Map<String, Object> response = new HashMap<>();
        response.put("signalType", "pong");
        response.put("timestamp", System.currentTimeMillis());
        response.put("message", "心跳正常");

        sendResponse(ctx, response);
    }


    /**
     * 处理通话请求
     */
    private void handleCallRequest(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        log.info("处理通话请求: {} 请求与 {} 通话", data.getSendUserId(), data.getReceiveUserId());

        if (!isUserOnline(data.getReceiveUserId())) {
            sendErrorResponse(ctx, "目标用户不在线");
            return;
        }

        // 转发通话请求
        boolean success = forwardMessageToUser(data);
        if (success) {
            sendSuccessResponse(ctx, "call_request", "通话请求已发送");
        } else {
            sendErrorResponse(ctx, "发送通话请求失败");
        }
    }

    /**
     * 处理通话响应
     */
    private void handleCallResponse(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        log.info("处理通话响应: {} 响应 {} 的通话请求", data.getSendUserId(), data.getReceiveUserId());

        // 转发通话响应
        forwardMessageToUser(data);
    }

    /**
     * 处理摄像头开关通知，直接转发给对方
     */
    private void handleCameraToggle(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        log.info("处理摄像头切换通知: {} -> {}, enabled={}", data.getSendUserId(), data.getReceiveUserId(), data.getSignalData());
        forwardMessageToUser(data);
    }

    /**
     * 处理未知消息类型
     */
    private void handleUnknownMessage(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        log.warn("收到未知消息类型: {}", data.getSignalType());
        sendErrorResponse(ctx, "不支持的消息类型: " + data.getSignalType());
    }

    /**
     * 转发消息给目标用户
     */
    private boolean forwardMessageToUser(PeerConnectionDataDto data) {
        try {
            String receiveUserId = data.getReceiveUserId();
            String message = JSON.toJSONString(data);

            boolean sent = channelContextUtils.sendMessageToUser(receiveUserId, message);

            if (sent) {
                log.debug("消息转发成功: {} -> {}", data.getSendUserId(), receiveUserId);
            } else {
                log.warn("消息转发失败，目标用户可能不在线: {} -> {}", data.getSendUserId(), receiveUserId);
                if (shouldNotifyOffline(data)) {
                    // 只有呼叫发起阶段需要回传离线提示；挂断和群通话信令失败不打扰当前通话窗口。
                    data.setSignalType("notOnline");
                    data.setReceiveUserId(data.getSendUserId());
                    message = JSON.toJSONString(data);
                    channelContextUtils.sendMessageToUser(data.getReceiveUserId(), message);
                }
            }

            return sent;

        } catch (Exception e) {
            log.error("转发消息时发生异常", e);
            return false;
        }
    }

    /**
     * 判断转发失败时是否需要通知发送方目标不在线。
     */
    private boolean shouldNotifyOffline(PeerConnectionDataDto data) {
        String signalType = data.getSignalType() == null ? "" : data.getSignalType().toLowerCase();
        if ("group".equals(data.getCallMode()) || data.getGroupId() != null) {
            return false;
        }
        return !"end_call".equals(signalType);
    }

    /**
     * 验证消息数据
     */
    private boolean validateMessageData(ChannelHandlerContext ctx, PeerConnectionDataDto data) {
        // 检查信令类型
        if (data.getSignalType() == null || data.getSignalType().trim().isEmpty()) {
            sendErrorResponse(ctx, "消息类型不能为空");
            return false;
        }

        // 对于需要目标用户的消息类型，检查接收者ID
        String signalType = data.getSignalType().toLowerCase();
        if (needsReceiveUser(signalType)) {
            if (data.getReceiveUserId() == null || data.getReceiveUserId().trim().isEmpty()) {
                sendErrorResponse(ctx, "接收用户ID不能为空");
                return false;
            }

            // 不能给自己发送消息
            if (data.getSendUserId().equals(data.getReceiveUserId())) {
                sendErrorResponse(ctx, "不能给自己发送消息");
                return false;
            }
        }

        return true;
    }

    /**
     * 判断消息类型是否需要接收用户
     */
    private boolean needsReceiveUser(String signalType) {
        return !"heartbeat".equals(signalType) &&
                !"ping".equals(signalType) &&
                !"close_group_call".equals(signalType) &&
                !"query_group_call".equals(signalType) &&
                !"user_list".equals(signalType);
    }

    /**
     * 检查用户是否在线
     */
    private boolean isUserOnline(String userId) {
        Channel channel = channelContextUtils.getChannelByUserId(userId);
        return channel != null && channel.isActive();
    }

    /**
     * 发送成功响应
     */
    private void sendSuccessResponse(ChannelHandlerContext ctx, String type, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("type", type);
        response.put("message", message);
        response.put("timestamp", System.currentTimeMillis());

        sendResponse(ctx, response);
    }

    /**
     * 发送错误响应
     */
    private void sendErrorResponse(ChannelHandlerContext ctx, String error) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("type", "error");
        response.put("message", error);
        response.put("timestamp", System.currentTimeMillis());

        sendResponse(ctx, response);
        log.warn("发送错误响应: {}", error);
    }

    /**
     * 发送响应消息
     */
    private void sendResponse(ChannelHandlerContext ctx, Map<String, Object> response) {
        try {
            String json = JSON.toJSONString(response);
            ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            log.error("发送响应消息失败", e);
        }
    }

    /**
     * 记录消息日志
     */
    private void logMessage(PeerConnectionDataDto data) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        log.info("[{}] 信令消息 - 类型: {}, 发送者: {}, 接收者: {}",
                timestamp, data.getSignalType(), data.getSendUserId(), data.getReceiveUserId());

        // 如果是调试模式，还可以打印信令数据
        if (log.isDebugEnabled()) {
            log.debug("信令数据: {}",
                    data.getSignalData() != null ?
                            (data.getSignalData().length() > 200 ?
                                    data.getSignalData().substring(0, 200) + "..." :
                                    data.getSignalData()) : "null");
        }
    }

}
