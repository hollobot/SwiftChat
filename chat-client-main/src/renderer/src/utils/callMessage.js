import { send } from "@/api/chatApi";
import { escapeHtml } from "@/utils/stringUtils";

const CALL_LABEL = {
	audio: "语音通话",
	video: "视频通话"
};

export const formatCallDuration = (startTime, endTime = Date.now()) => {
	if (!startTime) {
		return "00:00";
	}
	const totalSeconds = Math.max(0, Math.floor((endTime - startTime) / 1000));
	const minutes = String(Math.floor(totalSeconds / 60)).padStart(2, "0");
	const seconds = String(totalSeconds % 60).padStart(2, "0");
	return `${minutes}:${seconds}`;
};

export const getCallEventText = ({ mediaType = "audio", event, durationText, memberCount }) => {
	const label = CALL_LABEL[mediaType] || CALL_LABEL.audio;
	const groupPrefix = memberCount ? `群${label}` : label;
	switch (event) {
		case "cancel":
			return `${groupPrefix}已取消`;
		case "reject":
			return `已拒绝${label}`;
		case "end":
			return `${groupPrefix}已结束，通话 ${durationText || "00:00"}`;
		default:
			return label;
	}
};

const escapeCallMessageContent = (messageContent, messageType) => {
	const safeContent = escapeHtml(messageContent);
	// 群通话系统消息用文本插值渲染，普通空格不需要转成 HTML 实体。
	if (messageType === 18) {
		return safeContent.replace(/&nbsp;/g, " ");
	}
	return safeContent;
};

/**
 * 发送通话事件到聊天记录，并同步写入本地库。
 * 通话信令仍走 WebSocket；这里按聊天消息写入，群通话可指定系统消息类型。
 */
export const sendCallEventMessage = async ({
	contactId,
	sessionId,
	recipientType,
	userInfo,
	messageContent,
	messageType = 2
}) => {
	if (!contactId || !userInfo?.userId || !messageContent) {
		return { success: false };
	}

	const safeContent = escapeCallMessageContent(messageContent, messageType);
	const result = await send({
		uuid: crypto.randomUUID(),
		contactId,
		messageContent: safeContent,
		messageType
	});

	if (result.code && result.code !== 200) {
		return { success: false, result };
	}

	const messageObj = {
		messageContent: safeContent,
		messageType,
		sessionId,
		sendUserId: userInfo.userId,
		userId: userInfo.userId,
		recipientId: contactId,
		recipientType,
		sendUserNickName: userInfo.nickName,
		sendTime: Date.now(),
		status: 1,
		uuid: crypto.randomUUID(),
		skipNoRead: true,
		fromCallWindow: true
	};

	if (result.data) {
		Object.assign(messageObj, result.data);
	}

	window.ipcRenderer.send("addLocalMessage", messageObj);
	return { success: true, message: messageObj };
};
