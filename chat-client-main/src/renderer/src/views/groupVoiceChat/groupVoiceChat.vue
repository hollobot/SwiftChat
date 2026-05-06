<template>
	<div class="group-voice-chat">
		<div class="top-bar drag">
			<div class="top-bar-left">
				<span class="title-text">{{ groupName || "群语音通话" }}</span>
				<span class="title-count">{{ connectedCount }}/{{ participantList.length }}</span>
			</div>
		</div>

		<div class="call-body">
			<div class="status-panel">
				<div class="status-title">{{ callStatusText }}</div>
				<div class="status-desc">{{ controlHint }}</div>
			</div>

			<div class="member-grid">
				<div v-for="member in participantList" :key="member.id" class="member-card">
					<div :class="['member-avatar', member.status]">
						<ShowLocalImage
							:width="52"
							:height="52"
							:file-id="member.id"
							part-type="avatar"
							:file-type="0"
						></ShowLocalImage>
					</div>
					<div class="member-name" :title="member.name">{{ member.name }}</div>
					<div :class="['member-status', member.status]">
						<span class="status-dot"></span>
						{{ memberStatusText(member.status) }}
					</div>
				</div>
			</div>

			<div class="incoming-call-panel" v-if="incomingCallVisible">
				<div class="incoming-title">收到群语音通话邀请</div>
				<div class="incoming-desc">
					{{ inviterName || "群成员" }} 邀请你加入 {{ groupName || "群语音通话" }}
				</div>
				<div class="incoming-actions">
					<button class="action-btn accept-btn" @click="acceptIncomingCall">加入</button>
					<button class="action-btn reject-btn" @click="rejectIncomingCall">拒绝</button>
				</div>
			</div>
		</div>

		<div ref="audioBox" class="audio-box"></div>

		<div class="controls-wrapper">
			<div class="controls-panel">
				<div class="controls-hint">{{ controlsText }}</div>
				<div class="controls">
					<button
						class="ctrl-btn"
						:class="{ off: !audioEnabled }"
						@click="toggleAudio"
						:title="audioEnabled ? '静音' : '取消静音'"
					>
						<span class="iconfont icon-dianhua3"></span>
					</button>
					<button class="ctrl-btn end-btn" @click="endCall" title="挂断">
						<span class="iconfont icon-dianhua3"></span>
					</button>
				</div>
			</div>
		</div>
	</div>
	<windowControlButton :win-config="windowControl"></windowControlButton>
</template>

<script setup>
	import WindowControlButton from "@/components/windowControlButton.vue";
	import ShowLocalImage from "@/components/showLocalImage.vue";
	import { computed, onMounted, onUnmounted, ref } from "vue";
	import { ElMessage } from "element-plus";
	import { useUserInfoStore } from "@/stores/userInfoStore";
	import { storeToRefs } from "pinia";
	import {
		formatCallDuration,
		getCallEventText,
		sendCallEventMessage
	} from "@/utils/callMessage";

	const userInfoStore = useUserInfoStore();
	const { userInfo } = storeToRefs(userInfoStore);

	const currentUserId = ref("");
	const currentUserName = ref("");
	const groupId = ref("");
	const groupName = ref("");
	const sessionId = ref("");
	const callId = ref("");
	const inviterId = ref("");
	const inviterName = ref("");
	const participantList = ref([]);
	const incomingCallVisible = ref(false);
	const isInCall = ref(false);
	const audioEnabled = ref(true);
	const audioBox = ref(null);
	const isCaller = ref(false);

	const windowControl = {
		isShowPin: true,
		isShowMinimize: true,
		isShowMaximize: true,
		closeType: 0
	};

	const INVITE_TIMEOUT = 30000;
	const REMOVE_MEMBER_DELAY = 2000;
	const ACTIVE_MEMBER_STATUS = ["self", "joined", "connected"];
	const PENDING_MEMBER_STATUS = ["inviting", "ringing"];
	const FINAL_MEMBER_STATUS = ["rejected", "no_answer"];

	let localStream = null;
	let callStartedAt = null;
	let hasSentFinalMessage = false;
	let isClosing = false;
	const peerMap = new Map();
	const pendingIceMap = new Map();
	const joinedUserIds = new Set();
	const remoteAudioMap = new Map();
	const inviteTimerMap = new Map();
	const removeTimerMap = new Map();
	const expiredInviteIds = new Set();
	let incomingInviteTimer = null;

	const ICE_SERVERS = [
		{ urls: "stun:stun.l.google.com:19302" },
		{ urls: "stun:stun1.l.google.com:19302" },
		{
			urls: "turn:openrelay.metered.ca:80",
			username: "openrelayproject",
			credential: "openrelayproject"
		}
	];

	const connectedCount = computed(() => {
		return participantList.value.filter((member) =>
			["self", "joined", "connected"].includes(member.status)
		).length;
	});

	const callStatusText = computed(() => {
		if (incomingCallVisible.value) return "等待你选择是否加入";
		if (isInCall.value) return "群语音通话中";
		return "准备群语音通话";
	});

	const controlHint = computed(() => {
		if (incomingCallVisible.value) return "加入后会与已在线成员建立点对点语音连接";
		if (isInCall.value) return "成员加入后会自动建立语音连接";
		return "正在准备麦克风";
	});

	const controlsText = computed(() => {
		return audioEnabled.value ? "麦克风已开启" : "麦克风已静音";
	});

	const memberStatusText = (status) => {
		const map = {
			self: "我",
			inviting: "邀请中",
			ringing: "待接听",
			joined: "已加入",
			connected: "已连线",
			rejected: "已拒绝",
			no_answer: "未接听",
			left: "已离开"
		};
		return map[status] || "等待中";
	};

	const normalizeMember = (member) => {
		return {
			id: member.id || member.userId,
			name: member.name || member.nickName || member.contactName || member.id || member.userId,
			status: member.status || "inviting"
		};
	};

	const upsertMember = (member) => {
		const next = normalizeMember(member);
		if (!next.id) return;
		const exist = participantList.value.find((item) => item.id === next.id);
		if (exist) {
			Object.assign(exist, next);
		} else {
			participantList.value.push(next);
		}
	};

	const clearInviteTimer = (userId) => {
		const timer = inviteTimerMap.get(userId);
		if (timer) {
			clearTimeout(timer);
			inviteTimerMap.delete(userId);
		}
	};

	const clearRemoveTimer = (userId) => {
		const timer = removeTimerMap.get(userId);
		if (timer) {
			clearTimeout(timer);
			removeTimerMap.delete(userId);
		}
	};

	const clearIncomingInviteTimer = () => {
		if (incomingInviteTimer) {
			clearTimeout(incomingInviteTimer);
			incomingInviteTimer = null;
		}
	};

	const clearAllMemberTimers = () => {
		inviteTimerMap.forEach((timer) => clearTimeout(timer));
		removeTimerMap.forEach((timer) => clearTimeout(timer));
		inviteTimerMap.clear();
		removeTimerMap.clear();
		clearIncomingInviteTimer();
	};

	const setMemberStatus = (userId, status) => {
		const member = participantList.value.find((item) => item.id === userId);
		if (member) {
			member.status =
				userId === currentUserId.value && ACTIVE_MEMBER_STATUS.includes(status) ? "self" : status;
		}
		if (!PENDING_MEMBER_STATUS.includes(status)) {
			clearInviteTimer(userId);
		}
	};

	const removeMember = (userId) => {
		if (!userId || userId === currentUserId.value) return;
		clearInviteTimer(userId);
		clearRemoveTimer(userId);
		closePeer(userId);
		joinedUserIds.delete(userId);
		participantList.value = participantList.value.filter((member) => member.id !== userId);
	};

	const scheduleMemberRemoval = (userId) => {
		if (!userId || userId === currentUserId.value) return;
		clearRemoveTimer(userId);
		removeTimerMap.set(
			userId,
			setTimeout(() => removeMember(userId), REMOVE_MEMBER_DELAY)
		);
	};

	const applyFinalMemberStatus = (userId, status) => {
		const member = participantList.value.find((item) => item.id === userId);
		if (!member || userId === currentUserId.value) return;
		expiredInviteIds.add(userId);
		closePeer(userId);
		joinedUserIds.delete(userId);
		setMemberStatus(userId, status);
		if (FINAL_MEMBER_STATUS.includes(status)) {
			scheduleMemberRemoval(userId);
		}
	};

	const broadcastMemberStatus = (userId, status) => {
		const member = participantList.value.find((item) => item.id === userId);
		participantList.value.forEach((item) => {
			if (item.id !== currentUserId.value) {
				sendSignalTo(item.id, "member_status", {
					userId,
					name: member?.name || userId,
					status
				});
			}
		});
	};

	const markInviteFailed = (userId, status = "no_answer", notify = true) => {
		applyFinalMemberStatus(userId, status);
		if (notify) {
			broadcastMemberStatus(userId, status);
		}
	};

	const startInviteTimer = (userId) => {
		if (!userId || userId === currentUserId.value) return;
		clearInviteTimer(userId);
		expiredInviteIds.delete(userId);
		inviteTimerMap.set(
			userId,
			setTimeout(() => {
				const member = participantList.value.find((item) => item.id === userId);
				if (member && PENDING_MEMBER_STATUS.includes(member.status)) {
					markInviteFailed(userId, "no_answer");
				}
			}, INVITE_TIMEOUT)
		);
	};

	const getActiveRemoteMembers = () => {
		return participantList.value.filter((member) => {
			return member.id !== currentUserId.value && ACTIVE_MEMBER_STATUS.includes(member.status);
		});
	};

	const isLastActiveMember = () => {
		return isInCall.value && getActiveRemoteMembers().length === 0;
	};

	const initMembers = (members = []) => {
		participantList.value = [];
		members.forEach((member) => upsertMember(member));
		upsertMember({
			id: currentUserId.value,
			name: currentUserName.value || userInfo.value?.nickName || "我",
			status: "self"
		});
	};

	const parseSignalData = (message) => {
		try {
			return JSON.parse(message.signalData || "{}");
		} catch (error) {
			console.error("[GroupVoice] 解析信令失败:", error);
			return {};
		}
	};

	const getBaseSignal = (receiveUserId, signalType, signalData = {}) => {
		return {
			sendUserId: currentUserId.value,
			receiveUserId,
			signalType,
			signalData: JSON.stringify(signalData),
			messageType: 17,
			callId: callId.value,
			callMode: "group",
			mediaType: "audio",
			groupId: groupId.value,
			groupName: groupName.value
		};
	};

	const sendSignalTo = (receiveUserId, signalType, signalData = {}) => {
		if (!receiveUserId || receiveUserId === currentUserId.value) return;
		window.ipcRenderer.send(
			"groupvoicertc:send-signal",
			getBaseSignal(receiveUserId, signalType, signalData)
		);
	};

	const broadcastSignal = (signalType, signalData = {}, allowedStatus = ACTIVE_MEMBER_STATUS) => {
		participantList.value.forEach((member) => {
			if (
				member.id !== currentUserId.value &&
				allowedStatus.includes(member.status)
			) {
				sendSignalTo(member.id, signalType, signalData);
			}
		});
	};

	const getLocalMedia = async () => {
		try {
			const newStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
			if (localStream) {
				localStream.getTracks().forEach((track) => track.stop());
			}
			localStream = newStream;
			audioEnabled.value = true;
			return true;
		} catch (error) {
			console.error("[GroupVoice] 获取麦克风失败:", error);
			ElMessage.error("无法获取麦克风权限");
			return false;
		}
	};

	const shouldCreateOffer = (remoteUserId) => {
		return String(currentUserId.value) < String(remoteUserId);
	};

	const createPeerConnection = async (remoteUserId) => {
		const exist = peerMap.get(remoteUserId);
		if (exist && exist.connectionState !== "closed") {
			return exist;
		}

		const peer = new RTCPeerConnection({
			iceServers: ICE_SERVERS,
			iceCandidatePoolSize: 10,
			iceTransportPolicy: "all",
			bundlePolicy: "max-bundle",
			rtcpMuxPolicy: "require"
		});

		if (localStream) {
			localStream.getTracks().forEach((track) => peer.addTrack(track, localStream));
		}

		peer.ontrack = (event) => {
			attachRemoteAudio(remoteUserId, event.streams[0]);
			setMemberStatus(remoteUserId, "connected");
		};

		peer.onicecandidate = (event) => {
			if (event.candidate) {
				sendSignalTo(remoteUserId, "candidate", event.candidate);
			}
		};

		peer.onconnectionstatechange = () => {
			if (peer.connectionState === "connected") {
				setMemberStatus(remoteUserId, "connected");
			} else if (peer.connectionState === "disconnected" || peer.connectionState === "failed") {
				setMemberStatus(remoteUserId, "joined");
			}
		};

		peerMap.set(remoteUserId, peer);
		return peer;
	};

	const attachRemoteAudio = (remoteUserId, stream) => {
		let audio = remoteAudioMap.get(remoteUserId);
		if (!audio) {
			audio = document.createElement("audio");
			audio.autoplay = true;
			audioBox.value?.appendChild(audio);
			remoteAudioMap.set(remoteUserId, audio);
		}
		audio.srcObject = stream;
	};

	const ensureConnectionWith = async (remoteUserId) => {
		if (!isInCall.value || remoteUserId === currentUserId.value) return;
		const peer = await createPeerConnection(remoteUserId);
		if (shouldCreateOffer(remoteUserId) && peer.signalingState === "stable") {
			const offer = await peer.createOffer({
				offerToReceiveAudio: true,
				offerToReceiveVideo: false
			});
			await peer.setLocalDescription(offer);
			sendSignalTo(remoteUserId, "offer", offer);
		}
	};

	const flushPendingCandidates = async (remoteUserId) => {
		const peer = peerMap.get(remoteUserId);
		const pendingList = pendingIceMap.get(remoteUserId) || [];
		if (!peer || pendingList.length === 0) return;
		pendingIceMap.set(remoteUserId, []);
		for (const candidate of pendingList) {
			try {
				await peer.addIceCandidate(new RTCIceCandidate(candidate));
			} catch (error) {
				console.warn("[GroupVoice] 应用 ICE 候选失败:", error.message);
			}
		}
	};

	const startOutgoingCall = async () => {
		const ok = await getLocalMedia();
		if (!ok) {
			await sendGroupCallMessage("cancel", { requireCaller: true });
			return;
		}

		isInCall.value = true;
		callStartedAt = Date.now();
		joinedUserIds.add(currentUserId.value);
		setMemberStatus(currentUserId.value, "self");

		const inviteData = {
			callId: callId.value,
			groupId: groupId.value,
			groupName: groupName.value,
			sessionId: sessionId.value,
			inviterId: currentUserId.value,
			inviterName: currentUserName.value,
			callStartedAt,
			participants: participantList.value
		};

		participantList.value.forEach((member) => {
			if (member.id !== currentUserId.value) {
				setMemberStatus(member.id, "inviting");
				startInviteTimer(member.id);
				sendSignalTo(member.id, "group_invite", inviteData);
			}
		});
	};

	const acceptIncomingCall = async () => {
		const ok = await getLocalMedia();
		if (!ok) return;

		clearIncomingInviteTimer();
		incomingCallVisible.value = false;
		isInCall.value = true;
		if (!callStartedAt) {
			callStartedAt = Date.now();
		}
		joinedUserIds.add(currentUserId.value);
		if (inviterId.value) {
			joinedUserIds.add(inviterId.value);
		}
		setMemberStatus(currentUserId.value, "self");

		broadcastSignal("join_call", {
			callId: callId.value,
			userId: currentUserId.value,
			name: currentUserName.value,
			callStartedAt
		});

		for (const remoteUserId of joinedUserIds) {
			await ensureConnectionWith(remoteUserId);
		}
	};

	const finishIncomingInvite = (status, notify = true) => {
		clearIncomingInviteTimer();
		incomingCallVisible.value = false;
		if (notify) {
			broadcastMemberStatus(currentUserId.value, status);
		}
		setMemberStatus(currentUserId.value, status);
		setTimeout(() => {
			cleanup();
			closeCurrentWindow();
		}, REMOVE_MEMBER_DELAY);
	};

	const rejectIncomingCall = () => {
		if (isClosing) return;
		isClosing = true;
		if (inviterId.value) {
			sendSignalTo(inviterId.value, "reject_call", {
				userId: currentUserId.value,
				name: currentUserName.value
			});
		}
		finishIncomingInvite("rejected");
	};

	const handleGroupInvite = (message) => {
		const data = parseSignalData(message);
		callId.value = message.callId || data.callId;
		groupId.value = message.groupId || data.groupId;
		groupName.value = message.groupName || data.groupName || "群语音通话";
		sessionId.value = data.sessionId || message.groupId || data.groupId;
		inviterId.value = message.sendUserId || data.inviterId;
		inviterName.value = data.inviterName || message.sendUserNickName || "群成员";
		callStartedAt = data.callStartedAt || Date.now();
		initMembers(data.participants || []);
		setMemberStatus(inviterId.value, "joined");
		incomingCallVisible.value = true;
		clearIncomingInviteTimer();
		incomingInviteTimer = setTimeout(() => {
			if (!isInCall.value && incomingCallVisible.value) {
				finishIncomingInvite("no_answer");
			}
		}, INVITE_TIMEOUT);
	};

	const handleJoinCall = async (message) => {
		const data = parseSignalData(message);
		const remoteUserId = message.sendUserId || data.userId;
		if (!remoteUserId || remoteUserId === currentUserId.value) return;
		if (expiredInviteIds.has(remoteUserId)) return;
		clearInviteTimer(remoteUserId);
		clearRemoveTimer(remoteUserId);
		if (data.callStartedAt && !callStartedAt) {
			callStartedAt = data.callStartedAt;
		}
		joinedUserIds.add(remoteUserId);
		upsertMember({
			id: remoteUserId,
			name: data.name || message.sendUserNickName || remoteUserId,
			status: "joined"
		});
		if (isInCall.value) {
			await ensureConnectionWith(remoteUserId);
		}
	};

	const handleOffer = async (message) => {
		if (!isInCall.value) return;
		const offer = parseSignalData(message);
		const remoteUserId = message.sendUserId;
		if (expiredInviteIds.has(remoteUserId)) return;
		clearInviteTimer(remoteUserId);
		const peer = await createPeerConnection(remoteUserId);
		await peer.setRemoteDescription(new RTCSessionDescription(offer));
		await flushPendingCandidates(remoteUserId);
		const answer = await peer.createAnswer();
		await peer.setLocalDescription(answer);
		sendSignalTo(remoteUserId, "answer", answer);
		joinedUserIds.add(remoteUserId);
		setMemberStatus(remoteUserId, "joined");
	};

	const handleAnswer = async (message) => {
		const answer = parseSignalData(message);
		const remoteUserId = message.sendUserId;
		if (expiredInviteIds.has(remoteUserId)) return;
		clearInviteTimer(remoteUserId);
		const peer = peerMap.get(remoteUserId);
		if (!peer || peer.remoteDescription || peer.signalingState !== "have-local-offer") return;
		await peer.setRemoteDescription(new RTCSessionDescription(answer));
		await flushPendingCandidates(remoteUserId);
		setMemberStatus(remoteUserId, "joined");
	};

	const handleCandidate = async (message) => {
		const candidate = parseSignalData(message);
		const remoteUserId = message.sendUserId;
		const peer = peerMap.get(remoteUserId);
		if (!peer || !peer.remoteDescription) {
			const pendingList = pendingIceMap.get(remoteUserId) || [];
			pendingList.push(candidate);
			pendingIceMap.set(remoteUserId, pendingList);
			return;
		}
		try {
			await peer.addIceCandidate(new RTCIceCandidate(candidate));
		} catch (error) {
			console.warn("[GroupVoice] 添加 ICE 候选失败:", error.message);
		}
	};

	const closePeer = (remoteUserId) => {
		const peer = peerMap.get(remoteUserId);
		if (peer) {
			peer.close();
			peerMap.delete(remoteUserId);
		}
		const audio = remoteAudioMap.get(remoteUserId);
		if (audio) {
			audio.srcObject = null;
			audio.remove();
			remoteAudioMap.delete(remoteUserId);
		}
	};

	const handleMemberLeave = (message) => {
		const remoteUserId = message.sendUserId;
		closePeer(remoteUserId);
		joinedUserIds.delete(remoteUserId);
		setMemberStatus(remoteUserId, "left");
		if (!isInCall.value && incomingCallVisible.value && remoteUserId === inviterId.value) {
			incomingCallVisible.value = false;
			cleanup();
			closeCurrentWindow();
		}
	};

	const handleRejectCall = (message) => {
		const remoteUserId = message.sendUserId;
		if (expiredInviteIds.has(remoteUserId)) return;
		applyFinalMemberStatus(remoteUserId, "rejected");
	};

	const handleMemberStatus = (message) => {
		const data = parseSignalData(message);
		const remoteUserId = data.userId || message.sendUserId;
		const status = data.status;
		if (!remoteUserId || !FINAL_MEMBER_STATUS.includes(status)) return;
		if (remoteUserId === currentUserId.value) {
			if (!isInCall.value && incomingCallVisible.value) {
				finishIncomingInvite(status, false);
			}
			return;
		}
		if (expiredInviteIds.has(remoteUserId)) return;
		applyFinalMemberStatus(remoteUserId, status);
	};

	const handleSignalMessage = async (message) => {
		if (message.signalType === "notOnline") {
			return;
		}
		if (message.callId && callId.value && message.callId !== callId.value) {
			return;
		}

		try {
			switch (message.signalType) {
				case "group_invite":
					handleGroupInvite(message);
					break;
				case "join_call":
					await handleJoinCall(message);
					break;
				case "offer":
					await handleOffer(message);
					break;
				case "answer":
					await handleAnswer(message);
					break;
				case "candidate":
					await handleCandidate(message);
					break;
				case "leave_call":
					handleMemberLeave(message);
					break;
				case "reject_call":
					handleRejectCall(message);
					break;
				case "member_status":
					handleMemberStatus(message);
					break;
			}
		} catch (error) {
			console.error("[GroupVoice] 处理信令失败:", message.signalType, error);
		}
	};

	const sendGroupCallMessage = async (event, options = {}) => {
		if (options.requireCaller && !isCaller.value) return;
		if (hasSentFinalMessage || !groupId.value) return;
		hasSentFinalMessage = true;
		await sendCallEventMessage({
			contactId: groupId.value,
			sessionId: sessionId.value,
			recipientType: 1,
			messageType: 18,
			userInfo: {
				userId: currentUserId.value,
				nickName: currentUserName.value
			},
			messageContent: getCallEventText({
				mediaType: "audio",
				event,
				durationText: formatCallDuration(callStartedAt),
				memberCount: participantList.value.length
			})
		});
	};

	const sendFinalCallMessage = async () => {
		if (!isLastActiveMember()) return;
		await sendGroupCallMessage("end");
	};

	const endCall = async () => {
		if (isClosing) return;
		isClosing = true;
		if (isInCall.value) {
			broadcastSignal(
				"leave_call",
				{
					userId: currentUserId.value,
					name: currentUserName.value
				},
				[...ACTIVE_MEMBER_STATUS, ...PENDING_MEMBER_STATUS]
			);
			await sendFinalCallMessage();
		}
		cleanup();
		closeCurrentWindow();
	};

	const closeCurrentWindow = () => {
		window.ipcRenderer.send("sendWinControl", { action: "close", type: 0, force: true });
	};

	const handleWindowBeforeClose = () => {
		if (incomingCallVisible.value && !isInCall.value) {
			rejectIncomingCall();
			return;
		}
		endCall();
	};

	const cleanup = () => {
		isInCall.value = false;
		incomingCallVisible.value = false;
		clearAllMemberTimers();
		if (localStream) {
			localStream.getTracks().forEach((track) => track.stop());
			localStream = null;
		}
		Array.from(peerMap.keys()).forEach((remoteUserId) => closePeer(remoteUserId));
		pendingIceMap.clear();
		joinedUserIds.clear();
		expiredInviteIds.clear();
	};

	const toggleAudio = () => {
		const track = localStream?.getAudioTracks?.()[0];
		if (!track) return;
		track.enabled = !track.enabled;
		audioEnabled.value = track.enabled;
	};

	const setupIpcListeners = () => {
		window.ipcRenderer.on("groupvoicertc:signal-message", async (event, message) => {
			await handleSignalMessage(message);
		});
		window.ipcRenderer.on("groupvoicertc:connection-error", (event, error) => {
			console.error("[GroupVoice] 连接错误:", error);
		});
	};

	const remover = () => {
		window.ipcRenderer.removeAllListeners("pageInitData");
		window.ipcRenderer.removeAllListeners("groupvoicertc:signal-message");
		window.ipcRenderer.removeAllListeners("groupvoicertc:connection-error");
		window.ipcRenderer.removeAllListeners("groupvoicertc:connection-status");
	};

	onMounted(() => {
		remover();
		window.ipcRenderer.on("pageInitData", async (event, data) => {
			cleanup();
			callStartedAt = null;
			hasSentFinalMessage = false;
			isClosing = false;
			currentUserId.value = data.useId;
			currentUserName.value = data.currentUserName || userInfo.value?.nickName || "我";
			groupId.value = data.groupId;
			groupName.value = data.groupName || "群语音通话";
			sessionId.value = data.sessionId || data.groupId;
			callId.value = data.callId || crypto.randomUUID();
			inviterId.value = data.inviterId || data.useId;
			inviterName.value = data.inviterName || data.currentUserName;
			isCaller.value = data.isCaller === true;
			initMembers(data.participants || []);
			incomingCallVisible.value = data.incoming === true;

			if (data.autoStart) {
				await startOutgoingCall();
			}
		});
		setupIpcListeners();
		window.ipcRenderer.on("call-window:before-close", handleWindowBeforeClose);
	});

	onUnmounted(() => {
		if (isInCall.value) {
			if (isLastActiveMember()) {
				sendGroupCallMessage("end");
			}
			broadcastSignal(
				"leave_call",
				{
					userId: currentUserId.value,
					name: currentUserName.value
				},
				[...ACTIVE_MEMBER_STATUS, ...PENDING_MEMBER_STATUS]
			);
		}
		cleanup();
		window.ipcRenderer.removeListener("call-window:before-close", handleWindowBeforeClose);
		remover();
	});
</script>

<style lang="scss" scoped>
	.group-voice-chat {
		user-select: none;
		height: 100vh;
		width: 100vw;
		display: flex;
		flex-direction: column;
		background: #f7f8fa;
		box-sizing: border-box;
		overflow: hidden;

		.top-bar {
			height: 52px;
			flex-shrink: 0;
			display: flex;
			align-items: center;
			padding: 0 52px 0 18px;
			-webkit-app-region: drag;
			background: rgba(255, 255, 255, 0.94);
			border-bottom: 1px solid rgba(15, 23, 42, 0.08);

			.top-bar-left {
				display: flex;
				align-items: center;
				gap: 10px;
			}

			.title-text {
				color: #111827;
				font-size: 15px;
				font-weight: 600;
			}

			.title-count {
				color: #6b7280;
				font-size: 12px;
			}
		}

		.call-body {
			flex: 1;
			position: relative;
			display: flex;
			flex-direction: column;
			gap: 16px;
			padding: 24px;
			min-height: 0;
		}

		.status-panel {
			text-align: center;

			.status-title {
				font-size: 22px;
				font-weight: 600;
				color: #111827;
			}

			.status-desc {
				margin-top: 8px;
				font-size: 13px;
				color: #6b7280;
			}
		}

		.member-grid {
			flex: 1;
			min-height: 0;
			overflow-y: auto;
			display: grid;
			grid-template-columns: repeat(auto-fit, minmax(126px, 1fr));
			align-content: start;
			gap: 14px;
			padding: 2px 4px 8px;
		}

		.member-card {
			min-height: 136px;
			border-radius: 8px;
			background: linear-gradient(180deg, #ffffff 0%, #f9fafb 100%);
			border: 1px solid rgba(15, 23, 42, 0.08);
			display: flex;
			flex-direction: column;
			align-items: center;
			justify-content: center;
			gap: 8px;
			min-width: 0;
			padding: 14px 10px;
			box-shadow: 0 8px 20px rgba(15, 23, 42, 0.05);
			transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
		}

		.member-card:hover {
			border-color: rgba(31, 168, 86, 0.28);
			box-shadow: 0 12px 26px rgba(15, 23, 42, 0.08);
			transform: translateY(-1px);
		}

		.member-avatar {
			width: 58px;
			height: 58px;
			border-radius: 50%;
			display: flex;
			align-items: center;
			justify-content: center;
			border: 2px solid #d1d5db;
			background: #ffffff;
			overflow: hidden;
			flex-shrink: 0;

			:deep(.image-panel),
			:deep(img) {
				width: 52px;
				height: 52px;
				border-radius: 50%;
				object-fit: cover;
				display: block;
			}

			&.self,
			&.joined,
			&.connected {
				border-color: #22c55e;
			}

			&.inviting,
			&.ringing {
				border-color: #f59e0b;
			}

			&.rejected,
			&.no_answer,
			&.left {
				border-color: #ef4444;
			}
		}

		.member-name {
			width: 100%;
			text-align: center;
			font-size: 13px;
			font-weight: 600;
			color: #111827;
			overflow: hidden;
			text-overflow: ellipsis;
			white-space: nowrap;
		}

		.member-status {
			height: 24px;
			max-width: 100%;
			display: inline-flex;
			align-items: center;
			justify-content: center;
			gap: 5px;
			padding: 0 9px;
			border-radius: 999px;
			background: #f3f4f6;
			font-size: 12px;
			color: #6b7280;
			white-space: nowrap;

			.status-dot {
				width: 6px;
				height: 6px;
				border-radius: 50%;
				background: currentColor;
				flex-shrink: 0;
			}

			&.self,
			&.joined,
			&.connected {
				background: #dcfce7;
				color: #15803d;
			}

			&.inviting,
			&.ringing {
				background: #fef3c7;
				color: #b45309;
			}

			&.rejected,
			&.left,
			&.no_answer {
				background: #fee2e2;
				color: #b91c1c;
			}
		}

		.incoming-call-panel {
			position: absolute;
			left: 50%;
			bottom: 24px;
			transform: translateX(-50%);
			z-index: 5;
			min-width: 320px;
			padding: 18px 22px;
			border-radius: 8px;
			background: rgba(255, 255, 255, 0.96);
			box-shadow: 0 18px 40px rgba(15, 23, 42, 0.1);
			border: 1px solid rgba(15, 23, 42, 0.08);
			text-align: center;

			.incoming-title {
				font-size: 17px;
				font-weight: 600;
				color: #111827;
			}

			.incoming-desc {
				margin-top: 6px;
				font-size: 13px;
				color: #6b7280;
			}

			.incoming-actions {
				margin-top: 16px;
				display: flex;
				justify-content: center;
				gap: 14px;
			}

			.action-btn {
				min-width: 108px;
				height: 40px;
				border: none;
				border-radius: 20px;
				font-size: 14px;
				cursor: pointer;
			}

			.accept-btn {
				background: #1fa856;
				color: #ffffff;
			}

			.reject-btn {
				background: #edf2ee;
				color: #4f5f57;
			}
		}

		.audio-box {
			display: none;
		}

		.controls-wrapper {
			padding: 0 18px 18px;
		}

		.controls-panel {
			border-radius: 8px;
			padding: 14px 20px 18px;
			background: #ffffff;
			border: 1px solid rgba(15, 23, 42, 0.08);
			box-shadow: 0 10px 26px rgba(15, 23, 42, 0.08);
		}

		.controls-hint {
			text-align: center;
			color: #6b7280;
			font-size: 12px;
			margin-bottom: 12px;
		}

		.controls {
			display: flex;
			align-items: center;
			justify-content: center;
			gap: 32px;

			.ctrl-btn {
				width: 52px;
				height: 52px;
				border-radius: 50%;
				border: none;
				background: #f3f4f6;
				color: #374151;
				cursor: pointer;
				display: flex;
				align-items: center;
				justify-content: center;

				&.off {
					background: rgba(255, 69, 58, 0.72);
					color: #ffffff;
				}

				&.end-btn {
					width: 62px;
					height: 62px;
					background: #ff3b30;
					color: #ffffff;

					.iconfont {
						transform: rotate(135deg);
					}
				}
			}
		}

		@media (max-width: 520px) {
			.call-body {
				padding: 18px 14px;
			}

			.member-grid {
				grid-template-columns: repeat(auto-fit, minmax(108px, 1fr));
				gap: 10px;
			}

			.member-card {
				min-height: 126px;
				padding: 12px 8px;
			}

			.incoming-call-panel {
				left: 14px;
				right: 14px;
				min-width: 0;
				transform: none;
			}
		}
	}
</style>
