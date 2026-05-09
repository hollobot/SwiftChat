<template>
	<div :class="['group-voice-chat', { 'is-video-call': isVideoCall }]">
		<div class="top-bar drag">
			<div class="top-bar-left">
				<span class="title-text">{{ groupName || callTitle }}</span>
				<span class="title-count">{{ connectedCount }}/{{ participantList.length }}</span>
			</div>
		</div>

		<div class="call-body">
			<div class="status-panel">
				<div class="status-title">{{ callStatusText }}</div>
				<div class="status-desc">{{ controlHint }}</div>
			</div>

			<div class="member-grid" :style="memberGridStyle">
				<div v-for="member in participantList" :key="member.id" class="member-card">
					<div class="member-media">
						<video
							v-if="isVideoCall"
							v-show="member.hasVideo && member.videoEnabled"
							:ref="(el) => setVideoRef(member.id, el)"
							class="member-video"
							autoplay
							playsinline
							muted
						></video>
						<div
							v-show="!isVideoCall || !member.hasVideo || !member.videoEnabled"
							:class="['member-avatar', member.status]"
						>
							<ShowLocalImage
								:width="52"
								:height="52"
								:file-id="member.id"
								part-type="avatar"
								:file-type="0"
							></ShowLocalImage>
						</div>
					</div>
					<div class="member-name" :title="member.name">{{ member.name }}</div>
					<div :class="['member-status', member.status]">
						<span class="status-dot"></span>
						{{ memberStatusText(member.status) }}
					</div>
				</div>
			</div>

			<div class="incoming-call-panel" v-if="incomingCallVisible">
				<div class="incoming-title">收到{{ callTitle }}邀请</div>
				<div class="incoming-desc">
					{{ inviterName || "群成员" }} 邀请你加入 {{ groupName || callTitle }}
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
					<button
						v-if="isVideoCall"
						class="ctrl-btn"
						:class="{ off: !videoEnabled }"
						@click="toggleVideo"
						:title="videoEnabled ? '关闭摄像头' : '开启摄像头'"
					>
						<span class="iconfont icon-video"></span>
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
	import { computed, nextTick, onMounted, onUnmounted, ref } from "vue";
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
	const viewportWidth = ref(window.innerWidth);
	const incomingCallVisible = ref(false);
	const isInCall = ref(false);
	const audioEnabled = ref(true);
	const videoEnabled = ref(true);
	const mediaType = ref("audio");
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
	let hasSentCloseRoomSignal = false;
	let isClosing = false;
	const peerMap = new Map();
	const pendingIceMap = new Map();
	const joinedUserIds = new Set();
	const remoteAudioMap = new Map();
	const videoElementMap = new Map();
	const mediaStreamMap = new Map();
	const pendingVideoOfferIds = new Set();
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

	const isVideoCall = computed(() => mediaType.value === "video");

	const memberGridStyle = computed(() => {
		const count = Math.max(participantList.value.length, 1);
		let columns = count <= 2 ? count : Math.ceil(Math.sqrt(count * 1.6));
		if (viewportWidth.value < 520) {
			columns = 1;
		} else if (viewportWidth.value < 640 && count > 1) {
			columns = Math.min(2, count);
		} else if (count <= 3) {
			columns = count;
		}
		const rows = Math.ceil(count / columns);
		return {
			gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
			gridTemplateRows: `repeat(${rows}, minmax(0, 1fr))`
		};
	});

	const callTitle = computed(() => {
		return isVideoCall.value ? "群视频通话" : "群语音通话";
	});

	const callStatusText = computed(() => {
		if (incomingCallVisible.value) return "等待你选择是否加入";
		if (isInCall.value) return `${callTitle.value}中`;
		return `准备${callTitle.value}`;
	});

	const controlHint = computed(() => {
		const mediaName = isVideoCall.value ? "音视频" : "语音";
		if (incomingCallVisible.value) return `加入后会与已在线成员建立点对点${mediaName}连接`;
		if (isInCall.value) return `成员加入后会自动建立${mediaName}连接`;
		return isVideoCall.value ? "正在准备摄像头和麦克风" : "正在准备麦克风";
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
			status: member.status || "inviting",
			hasVideo: member.hasVideo || false,
			videoEnabled: member.videoEnabled !== false
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

	// 成员手动重新加入现有通话时，需要清理此前的 reject/no_answer 失效标记，
	// 否则后续 join/offer/candidate 会被当成过期信令直接忽略。
	const restoreParticipantJoinEligibility = (userId) => {
		if (!userId) return;
		expiredInviteIds.delete(userId);
		clearInviteTimer(userId);
		clearRemoveTimer(userId);
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

	const getJoinableMembers = (members = []) => {
		return members.filter((member) => {
			return ACTIVE_MEMBER_STATUS.includes(member.status);
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

	const setMemberVideoState = (userId, enabled, hasVideo) => {
		const member = participantList.value.find((item) => item.id === userId);
		if (!member) return;
		if (enabled !== undefined) member.videoEnabled = enabled;
		if (hasVideo !== undefined) member.hasVideo = hasVideo;
	};

	const hasLiveVideoTrack = (stream) => {
		return stream?.getVideoTracks?.().some((track) => track.readyState === "live") || false;
	};

	const syncMemberVideoVisibility = (userId) => {
		const stream = mediaStreamMap.get(userId);
		setMemberVideoState(userId, undefined, hasLiveVideoTrack(stream));
	};

	const bindVideoTrackEvents = (userId, track) => {
		if (!track) return;
		track.onmute = () => syncMemberVideoVisibility(userId);
		track.onunmute = () => syncMemberVideoVisibility(userId);
		track.onended = () => setMemberVideoState(userId, undefined, false);
	};

	const bindVideoElement = (userId) => {
		const el = videoElementMap.get(userId);
		if (!el) return;
		el.srcObject = mediaStreamMap.get(userId) || null;
		// Keep autoplay stable after srcObject changes.
		el.muted = true;
		el.play?.().catch(() => {});
		el.onloadedmetadata = () => syncMemberVideoVisibility(userId);
		el.onloadeddata = () => syncMemberVideoVisibility(userId);
		el.oncanplay = () => syncMemberVideoVisibility(userId);
		el.onplaying = () => syncMemberVideoVisibility(userId);
		el.onresize = () => syncMemberVideoVisibility(userId);
		syncMemberVideoVisibility(userId);
	};

	const setVideoRef = (userId, el) => {
		if (!userId) return;
		if (el) {
			videoElementMap.set(userId, el);
			bindVideoElement(userId);
		} else {
			videoElementMap.delete(userId);
		}
	};

	const bindMemberStream = async (userId, stream) => {
		mediaStreamMap.set(userId, stream);
		// Show video by stream track state, not by the video element render timing.
		setMemberVideoState(userId, undefined, hasLiveVideoTrack(stream));
		bindVideoTrackEvents(userId, stream?.getVideoTracks?.()[0]);
		await nextTick();
		bindVideoElement(userId);
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
			messageType: isVideoCall.value ? 15 : 17,
			callId: callId.value,
			callMode: "group",
			mediaType: mediaType.value,
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

	// 最后一名成员离开时已经没有可广播对象，需要单独通知服务端清理群通话上下文。
	const sendGroupServerSignal = (signalType, signalData = {}) => {
		window.ipcRenderer.send(
			"groupvoicertc:send-signal",
			getBaseSignal(null, signalType, signalData)
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
		let newStream = null;
		try {
			newStream = await navigator.mediaDevices.getUserMedia({
				audio: true,
				video: isVideoCall.value
			});
		} catch (error) {
			if (!isVideoCall.value) {
				console.error("[GroupVoice] 获取麦克风失败:", error);
				ElMessage.error("无法获取麦克风权限");
				return false;
			}
			try {
				// 群视频允许摄像头不可用时降级为纯语音加入，避免整个通话失败。
				newStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
			} catch (audioError) {
				console.error("[GroupVoice] 获取音视频失败:", audioError);
				ElMessage.error("无法获取麦克风权限");
				return false;
			}
		}

		if (localStream) {
			localStream.getTracks().forEach((track) => track.stop());
		}
		localStream = newStream;
		const hasAudio = localStream?.getAudioTracks?.().some((track) => track.readyState === "live") || false;
		const hasVideo =
			localStream?.getVideoTracks?.().some((track) => track.enabled && track.readyState === "live") || false;
		audioEnabled.value = hasAudio;
		videoEnabled.value = hasVideo;
		setMemberVideoState(currentUserId.value, hasVideo, hasVideo);
		await bindMemberStream(currentUserId.value, localStream);
		return true;
	};

	const shouldCreateOffer = (remoteUserId) => {
		return String(currentUserId.value) < String(remoteUserId);
	};

	const sendVideoOfferWhenStable = async (remoteUserId, peer) => {
		if (!peer || peer.connectionState === "closed") return;
		if (peer.signalingState !== "stable") {
			pendingVideoOfferIds.add(remoteUserId);
			return;
		}
		pendingVideoOfferIds.delete(remoteUserId);
		const offer = await peer.createOffer({
			offerToReceiveAudio: true,
			offerToReceiveVideo: true
		});
		await peer.setLocalDescription(offer);
		sendSignalTo(remoteUserId, "offer", offer);
	};

	const flushPendingVideoOffer = async (remoteUserId) => {
		if (!pendingVideoOfferIds.has(remoteUserId)) return;
		await sendVideoOfferWhenStable(remoteUserId, peerMap.get(remoteUserId));
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
		if (isVideoCall.value && !localStream?.getVideoTracks?.().length) {
			// Reserve the video m-line so later camera enabling can reuse this transceiver.
			peer.addTransceiver("video", { direction: "recvonly" });
		}

		peer.ontrack = async (event) => {
			await attachRemoteMedia(remoteUserId, event.streams[0]);
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
		peer.onsignalingstatechange = () => {
			if (peer.signalingState !== "stable") return;
			// Camera can be enabled during negotiation; send the video offer when stable.
			flushPendingVideoOffer(remoteUserId).catch((error) => {
				console.error("[GroupVoice] 补发视频协商失败:", error);
			});
		};

		peerMap.set(remoteUserId, peer);
		return peer;
	};

	const attachRemoteMedia = async (remoteUserId, stream) => {
		let audio = remoteAudioMap.get(remoteUserId);
		if (!audio) {
			audio = document.createElement("audio");
			audio.autoplay = true;
			audioBox.value?.appendChild(audio);
			remoteAudioMap.set(remoteUserId, audio);
		}
		audio.srcObject = stream;
		if (isVideoCall.value) {
			await bindMemberStream(remoteUserId, stream);
		}
	};

	const ensureConnectionWith = async (remoteUserId) => {
		if (!isInCall.value || remoteUserId === currentUserId.value) return;
		const peer = await createPeerConnection(remoteUserId);
		if (shouldCreateOffer(remoteUserId) && peer.signalingState === "stable") {
			const offer = await peer.createOffer({
				offerToReceiveAudio: true,
				offerToReceiveVideo: isVideoCall.value
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
			mediaType: mediaType.value,
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

	const startDirectJoinCall = async () => {
		const ok = await getLocalMedia();
		if (!ok) return;

		isInCall.value = true;
		if (!callStartedAt) {
			callStartedAt = Date.now();
		}
		joinedUserIds.add(currentUserId.value);
		setMemberStatus(currentUserId.value, "self");

		const activeRemoteMembers = getActiveRemoteMembers();
		activeRemoteMembers.forEach((member) => joinedUserIds.add(member.id));
		broadcastSignal("join_call", {
			callId: callId.value,
			userId: currentUserId.value,
			name: currentUserName.value,
			callStartedAt
		});

		for (const member of activeRemoteMembers) {
			await ensureConnectionWith(member.id);
		}
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
		cleanup();
		closeCurrentWindow();
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
		mediaType.value = message.mediaType || data.mediaType || "audio";
		callId.value = message.callId || data.callId;
		groupId.value = message.groupId || data.groupId;
		groupName.value = message.groupName || data.groupName || callTitle.value;
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
		restoreParticipantJoinEligibility(remoteUserId);
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
		restoreParticipantJoinEligibility(remoteUserId);
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
		restoreParticipantJoinEligibility(remoteUserId);
		clearInviteTimer(remoteUserId);
		const peer = peerMap.get(remoteUserId);
		// 摄像头从无到有会重新协商，此时已有旧 remoteDescription，不能因此丢弃新 answer。
		if (!peer || peer.signalingState !== "have-local-offer") return;
		await peer.setRemoteDescription(new RTCSessionDescription(answer));
		await flushPendingCandidates(remoteUserId);
		setMemberStatus(remoteUserId, "joined");
	};

	const handleCandidate = async (message) => {
		const candidate = parseSignalData(message);
		const remoteUserId = message.sendUserId;
		restoreParticipantJoinEligibility(remoteUserId);
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
		mediaStreamMap.delete(remoteUserId);
		pendingVideoOfferIds.delete(remoteUserId);
		bindVideoElement(remoteUserId);
		setMemberVideoState(remoteUserId, false, false);
	};

	const handleMemberLeave = (message) => {
		const remoteUserId = message.sendUserId;
		closePeer(remoteUserId);
		joinedUserIds.delete(remoteUserId);
		setMemberStatus(remoteUserId, "left");
		scheduleMemberRemoval(remoteUserId);
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

	const handleCameraToggle = (message) => {
		const data = parseSignalData(message);
		const remoteUserId = message.sendUserId || data.userId;
		if (!remoteUserId || remoteUserId === currentUserId.value) return;
		const enabled = data.enabled !== false;
		if (!enabled) {
			setMemberVideoState(remoteUserId, false, false);
			return;
		}
		setMemberVideoState(remoteUserId, true, false);
		nextTick(() => syncMemberVideoVisibility(remoteUserId));
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
				case "camera_toggle":
					handleCameraToggle(message);
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
				mediaType: mediaType.value,
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

	const closeGroupCallRoomIfNeeded = () => {
		if (!isInCall.value || !isLastActiveMember() || hasSentCloseRoomSignal || !groupId.value) {
			return;
		}
		hasSentCloseRoomSignal = true;
		sendGroupServerSignal("close_group_call", {
			userId: currentUserId.value,
			name: currentUserName.value,
			callStartedAt
		});
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
			closeGroupCallRoomIfNeeded();
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
		endCall().catch((error) => {
			console.error("[GroupVoice] 关闭通话窗口失败:", error);
			closeCurrentWindow();
		});
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
		pendingVideoOfferIds.clear();
		joinedUserIds.clear();
		expiredInviteIds.clear();
		mediaStreamMap.clear();
		videoElementMap.forEach((el) => {
			el.srcObject = null;
		});
	};

	const toggleAudio = () => {
		const track = localStream?.getAudioTracks?.()[0];
		if (!track) return;
		track.enabled = !track.enabled;
		audioEnabled.value = track.enabled;
	};

	const publishLocalVideoTrack = async (videoTrack) => {
		if (!localStream || !videoTrack) return;
		for (const [remoteUserId, peer] of peerMap.entries()) {
			if (!peer || peer.connectionState === "closed") continue;
			let transceiver = peer.getTransceivers().find((item) => {
				return item.sender?.track?.kind === "video" || item.receiver?.track?.kind === "video";
			});
			if (!transceiver) {
				transceiver = peer.addTransceiver(videoTrack, { direction: "sendrecv", streams: [localStream] });
			} else if (transceiver.sender) {
				transceiver.direction = "sendrecv";
				await transceiver.sender.replaceTrack(videoTrack);
			} else {
				peer.addTrack(videoTrack, localStream);
			}
			await sendVideoOfferWhenStable(remoteUserId, peer);
		}
	};

	const ensureLocalVideoTrack = async () => {
		let track = localStream?.getVideoTracks?.()[0];
		if (track && track.readyState === "live") {
			return track;
		}
		try {
			const cameraStream = await navigator.mediaDevices.getUserMedia({ video: true });
			track = cameraStream.getVideoTracks()[0];
			if (!track) return null;
			if (!localStream) {
				localStream = new MediaStream();
			}
			localStream.getVideoTracks().forEach((item) => {
				item.stop();
				localStream.removeTrack(item);
			});
			localStream.addTrack(track);
			// Publish to peers only; local view binding is completed after videoEnabled is restored.
			await publishLocalVideoTrack(track);
			return track;
		} catch (error) {
			console.error("[GroupVoice] 开启摄像头失败:", error);
			ElMessage.warning("当前没有可用摄像头");
			return null;
		}
	};

	const toggleVideo = async () => {
		const currentTrack = localStream?.getVideoTracks?.()[0];
		const willEnable = !(currentTrack && currentTrack.readyState === "live" && currentTrack.enabled);
		const track = willEnable ? await ensureLocalVideoTrack() : currentTrack;
		if (!track) return;
		track.enabled = willEnable;
		videoEnabled.value = willEnable;
		if (!willEnable) {
			setMemberVideoState(currentUserId.value, false, false);
		} else {
			// The card uses member-level videoEnabled, so restore it with the global button state.
			setMemberVideoState(currentUserId.value, true, hasLiveVideoTrack(localStream));
			await bindMemberStream(currentUserId.value, localStream);
			setTimeout(() => syncMemberVideoVisibility(currentUserId.value), 300);
		}
		broadcastSignal("camera_toggle", {
			userId: currentUserId.value,
			enabled: willEnable
		});
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

	const updateViewportWidth = () => {
		viewportWidth.value = window.innerWidth;
	};

	onMounted(() => {
		remover();
		window.addEventListener("resize", updateViewportWidth);
		window.ipcRenderer.on("pageInitData", async (event, data) => {
			cleanup();
			callStartedAt = null;
			hasSentFinalMessage = false;
			hasSentCloseRoomSignal = false;
			isClosing = false;
			currentUserId.value = data.useId;
			currentUserName.value = data.currentUserName || userInfo.value?.nickName || "我";
			mediaType.value = data.mediaType || "audio";
			groupId.value = data.groupId;
			groupName.value = data.groupName || callTitle.value;
			sessionId.value = data.sessionId || data.groupId;
			callId.value = data.callId || crypto.randomUUID();
			callStartedAt = data.callStartedAt || null;
			inviterId.value = data.inviterId || data.useId;
			inviterName.value = data.inviterName || data.currentUserName;
			isCaller.value = data.isCaller === true;
			// Only show members that are actually in the existing room when joining directly.
			initMembers(
				data.directJoin ? getJoinableMembers(data.participants || []) : data.participants || []
			);
			incomingCallVisible.value = data.incoming === true;

			if (data.autoStart) {
				await startOutgoingCall();
			} else if (data.directJoin) {
				await startDirectJoinCall();
			}
		});
		setupIpcListeners();
		window.ipcRenderer.on("call-window:before-close", handleWindowBeforeClose);
	});

	onUnmounted(() => {
		if (isInCall.value) {
			if (isLastActiveMember()) {
				sendGroupCallMessage("end").catch((error) => {
					console.error("[GroupVoice] 发送结束消息失败:", error);
				});
			}
			broadcastSignal(
				"leave_call",
				{
					userId: currentUserId.value,
					name: currentUserName.value
				},
				[...ACTIVE_MEMBER_STATUS, ...PENDING_MEMBER_STATUS]
			);
			closeGroupCallRoomIfNeeded();
		}
		cleanup();
		window.ipcRenderer.removeListener("call-window:before-close", handleWindowBeforeClose);
		window.removeEventListener("resize", updateViewportWidth);
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
			overflow: hidden;
			display: grid;
			grid-template-columns: repeat(auto-fit, minmax(126px, 1fr));
			align-content: stretch;
			justify-content: stretch;
			gap: 14px;
			padding: 8px;
		}

		&.is-video-call {
			.member-video {
				flex: 1 1 auto;
				min-height: 0;
			}

			.member-avatar {
				position: relative;
				z-index: 1;
			}
		}

		.member-card {
			width: calc(100% - 8px);
			height: calc(100% - 8px);
			align-self: stretch;
			min-height: 0;
			box-sizing: border-box;
			border-radius: 8px;
			background: linear-gradient(180deg, #ffffff 0%, #f9fafb 100%);
			border: 1px solid rgba(15, 23, 42, 0.08);
			display: flex;
			flex-direction: column;
			align-items: center;
			justify-content: flex-start;
			gap: 8px;
			margin: 4px;
			min-width: 0;
			padding: 14px 10px;
			overflow: hidden;
			box-shadow: 0 8px 20px rgba(15, 23, 42, 0.05);
			transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
		}

		.member-card:hover {
			border-color: rgba(31, 168, 86, 0.28);
			box-shadow: 0 12px 26px rgba(15, 23, 42, 0.08);
			transform: translateY(-1px);
		}

		.member-media {
			position: relative;
			flex: 1 1 0;
			width: 100%;
			height: auto;
			min-height: 0;
			display: flex;
			align-items: center;
			justify-content: center;
			border-radius: 8px;
			background: #f3f4f6;
			overflow: hidden;
		}

		.member-video {
			width: 100%;
			height: 100%;
			min-height: 0;
			object-fit: cover;
			border-radius: 8px;
			background: #111827;
			display: block;
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
			flex-shrink: 0;
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
			flex-shrink: 0;
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
				height: calc(100% - 8px);
				min-height: 0;
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
