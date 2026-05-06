<template>
	<div class="voiceChat">
		<!-- 顶部拖拽栏 -->
		<div class="top-bar drag">
			<div class="top-bar-left">
				<span class="title-text">语音通话</span>
			</div>
		</div>

		<!-- 主区域：显示对方头像与通话状态 -->
		<div class="voice-area">
			<div class="avatar-section">
				<img v-if="remoteProfile.avatar" :src="remoteProfile.avatar" class="remote-avatar" alt="头像" />
				<div v-else class="remote-avatar fallback">{{ displayInitial }}</div>
				<div class="remote-name">{{ displayName }}</div>
				<div class="call-status">{{ callStatusText }}</div>
			</div>

			<!-- 来电面板 -->
			<div class="incoming-call-panel" v-if="incomingCallVisible">
				<div class="incoming-title">收到语音通话邀请</div>
				<div class="incoming-desc">{{ displayName }} 正在呼叫你</div>
				<div class="incoming-actions">
					<button class="action-btn accept-btn" @click="acceptIncomingCall">接听</button>
					<button class="action-btn reject-btn" @click="rejectIncomingCall">拒绝</button>
				</div>
			</div>
		</div>

		<!-- 隐藏的音频元素，用于播放远端音频流 -->
		<audio id="remoteAudio" autoplay></audio>

		<!-- 底部控制栏 -->
		<div class="controls-wrapper">
			<div class="controls-panel">
				<div class="controls-hint">{{ controlHint }}</div>
				<div class="controls">
					<!-- 静音按钮 -->
					<button
						class="ctrl-btn"
						:class="{ off: !audioEnabled }"
						@click="toggleAudio"
						:title="audioEnabled ? '静音' : '取消静音'"
					>
						<svg viewBox="0 0 24 24" fill="currentColor" width="22" height="22">
							<path
								d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z"
							/>
						</svg>
					</button>

					<!-- 挂断按钮 -->
					<button class="ctrl-btn end-btn" @click="endCall" title="结束通话">
						<svg
							viewBox="0 0 24 24"
							fill="currentColor"
							width="22"
							height="22"
							style="transform: rotate(135deg)"
						>
							<path
								d="M20.01 15.38c-1.23 0-2.42-.2-3.53-.56-.35-.12-.74-.03-1.01.24l-1.57 1.97c-2.83-1.35-5.48-3.9-6.89-6.83l1.95-1.66c.27-.28.35-.67.24-1.02-.37-1.12-.56-2.3-.56-3.53 0-.54-.45-.99-.99-.99H4.19C3.65 5 3 5.24 3 5.99 3 13.7 9.3 20 17.01 20c.71 0 .99-.63.99-1.18v-2.45c0-.54-.45-.99-.99-.99z"
							/>
						</svg>
					</button>
				</div>
			</div>
		</div>
	</div>
	<windowControlButton :win-config="windowControl"></windowControlButton>
</template>

<script setup>
	import WindowControlButton from "@/components/windowControlButton.vue";
	import { onMounted, onUnmounted, ref, computed } from "vue";
	import { ElMessage } from "element-plus";
	import avatar from "@/assets/image/avatar/avatar.jpg";
	import { useUserInfoStore } from "@/stores/userInfoStore";
	import { storeToRefs } from "pinia";
	import {
		formatCallDuration,
		getCallEventText,
		sendCallEventMessage
	} from "@/utils/callMessage";

	const userInfoStore = useUserInfoStore();
	const { userInfo } = storeToRefs(userInfoStore);

	// 基本状态
	const currentUserId = ref("");
	const targetUserId = ref("");
	const isInCall = ref(false);
	const answerReceived = ref(false);
	const isConnected = ref(false); // P2P 连接已成功建立（connectionState === "connected"）
	const incomingCallVisible = ref(false);
	const pendingOffer = ref(null);
	const audioEnabled = ref(true);
	const isCaller = ref(false);

	const windowControl = {
		isShowPin: true,
		isShowMinimize: true,
		isShowMaximize: true,
		closeType: 0
	};

	const initData = ref();
	const remoteProfile = ref({
		name: "好友",
		avatar: avatar,
		userId: ""
	});

	const displayName = computed(() => {
		return remoteProfile.value.name || initData.value?.contactName || "好友";
	});

	const displayInitial = computed(() => {
		return displayName.value?.slice(0, 1)?.toUpperCase() || "友";
	});

	const callStatusText = computed(() => {
		if (incomingCallVisible.value) return "等待你选择接听或拒绝";
		// 以 P2P 连接状态（而非音频流）作为"已接通"判断依据
		if (isConnected.value) return "通话中";
		if (answerReceived.value) return "对方已接听，正在连接...";
		if (isInCall.value) return "正在等待对方接听...";
		return "准备中";
	});

	const controlHint = computed(() => {
		if (isConnected.value) return "语音通话已连线";
		if (answerReceived.value) return "对方已接听，正在建立连接...";
		if (isInCall.value) return "正在呼叫中，请保持窗口开启";
		return "准备发起语音通话";
	});

	let localStream = null;
	let peer = null;
	let isProcessingAnswer = false;
	let callStartedAt = null;
	let hasSentFinalMessage = false;
	let isClosing = false;
	// 远端描述就绪前缓存的 ICE 候选
	let pendingIceCandidates = [];

	const ICE_SERVERS = [
		{ urls: "stun:stun.l.google.com:19302" },
		{ urls: "stun:stun1.l.google.com:19302" },
		{
			urls: "turn:openrelay.metered.ca:80",
			username: "openrelayproject",
			credential: "openrelayproject"
		}
	];

	// 同步对方头像与昵称
	async function syncRemoteProfile(profile = {}) {
		remoteProfile.value = {
			name: profile.contactName || profile.targetNickName || remoteProfile.value.name || "好友",
			avatar: avatar,
			userId: profile.targetEmail || profile.recipient || remoteProfile.value.userId || ""
		};
		if (remoteProfile.value.userId) {
			const result = await window.ipcRenderer.invoke("getAvatarByUserId", remoteProfile.value.userId);
			if (result) remoteProfile.value.avatar = result;
		}
	}

	// 建立 RTCPeerConnection（仅需音频轨道）
	async function createPeerConnection() {
		if (peer) {
			peer.onicecandidate = null;
			peer.ontrack = null;
			peer.onconnectionstatechange = null;
			peer.close();
			peer = null;
		}
		pendingIceCandidates = [];

		peer = new RTCPeerConnection({
			iceServers: ICE_SERVERS,
			iceCandidatePoolSize: 10,
			iceTransportPolicy: "all",
			bundlePolicy: "max-bundle",
			rtcpMuxPolicy: "require"
		});

		// 把本地音频轨加入连接
		if (localStream) {
			localStream.getTracks().forEach((track) => peer.addTrack(track, localStream));
		}

		peer.ontrack = (event) => {
			// 将远端音频流绑定到隐藏 audio 元素以播放声音
			const remoteAudio = document.getElementById("remoteAudio");
			if (remoteAudio) remoteAudio.srcObject = event.streams[0];
		};

		peer.onicecandidate = (event) => {
			if (event.candidate) sendSignalMessage("candidate", event.candidate);
		};

		peer.onconnectionstatechange = () => {
			if (peer.connectionState === "connected") {
				isConnected.value = true;  // P2P 通路建立，切换提示为"已连线"
				answerReceived.value = false; // 清除中间态
				if (!callStartedAt) callStartedAt = Date.now();
			} else if (peer.connectionState === "failed") {
				isConnected.value = false;
			} else if (peer.connectionState === "disconnected") {
				isConnected.value = false;
				if (isCaller.value) sendVoiceCallMessage("end");
				endCall(false);
			}
		};
	}

	// 获取本地麦克风流（仅音频）
	async function getLocalMedia() {
		try {
			const newStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
			if (localStream) localStream.getTracks().forEach((t) => t.stop());
			localStream = newStream;
			audioEnabled.value = true;
			return true;
		} catch (error) {
			console.error("[VoiceChat] 获取麦克风失败:", error.message);
			return false;
		}
	}

	// 主动发起呼叫
	async function startCall() {
		if (!currentUserId.value || !targetUserId.value) return;

		isInCall.value = false;
		answerReceived.value = false;

		const hasLiveAudio = localStream && localStream.getAudioTracks().some((t) => t.readyState === "live");
		if (!hasLiveAudio) {
			const ok = await getLocalMedia();
			if (!ok) {
				await sendVoiceCallMessage("cancel");
				return;
			}
		}

		await createPeerConnection();

		// 仅协商音频，不协商视频
		const offer = await peer.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: false });
		await peer.setLocalDescription(offer);
		sendSignalMessage("offer", offer);
		isInCall.value = true;
	}

	// 发送信令（携带 messageType=17 让对端路由到语音通话窗口）
	function sendSignalMessage(signalType, signalData) {
		const message = {
			sendUserId: currentUserId.value,
			receiveUserId: targetUserId.value,
			signalType,
			signalData: JSON.stringify(signalData),
			messageType: 17 // 标识为语音通话，用于服务端透传和客户端路由
		};
		window.ipcRenderer.send("voicertc:send-signal", message);
	}

	// 设置 IPC 事件监听
	function setupIpcListeners() {
		window.ipcRenderer.on("voicertc:connection-status", (event, status) => {
			// 连接状态变化（暂不需要 UI 展示，保留供调试）
			console.log("[VoiceChat] 连接状态:", status);
		});

		ipcRenderer.on("voicertc:signal-message", async (event, message) => {
			await handleSignalMessage(message);
		});

		ipcRenderer.on("voicertc:connection-error", (event, error) => {
			console.error("[VoiceChat] 连接错误:", error);
		});
	}

	let flog = true;
	async function handleSignalMessage(message) {
		const { signalType, signalData, sendUserId, sendUserNickName } = message;

		if (signalType === "notOnline" && flog) {
			flog = false;
			ElMessage({ message: "对方可能不在线" });
			await sendVoiceCallMessage("cancel");
			setTimeout(() => {
				isInCall.value = false;
				if (!flog) flog = true;
			}, 1000);
			return;
		}

		try {
			const data = JSON.parse(signalData);
			switch (signalType) {
				case "offer":
					await handleOffer(data, sendUserId, sendUserNickName);
					break;
				case "answer":
					await handleAnswer(data);
					break;
				case "candidate":
					await handleCandidate(data);
					break;
				case "end_call":
					await handleEndCall();
					break;
				case "reject_call":
					await handleRejectCall();
					break;
				default:
					console.warn("[VoiceChat] 未知信令类型:", signalType);
			}
		} catch (error) {
			console.error("[VoiceChat] 处理信令失败:", signalType, error.message);
		}
	}

	// 处理来电 offer
	async function handleOffer(offer, sendUserId, sendUserNickName) {
		if (isInCall.value) return; // 已在通话中，忽略
		targetUserId.value = sendUserId;
		await syncRemoteProfile({ contactName: sendUserNickName, targetEmail: sendUserId, recipient: sendUserId });
		pendingOffer.value = offer;
		incomingCallVisible.value = true;
	}

	// 接听来电
	async function acceptIncomingCall() {
		if (!pendingOffer.value) return;
		incomingCallVisible.value = false;

		const hasLiveAudio = localStream && localStream.getAudioTracks().some((t) => t.readyState === "live");
		if (!hasLiveAudio) await getLocalMedia();

		if (peer && peer.connectionState !== "closed") {
			peer.close();
			peer = null;
			await new Promise((r) => setTimeout(r, 100));
		}

		await createPeerConnection();
		await peer.setRemoteDescription(new RTCSessionDescription(pendingOffer.value));
		await flushPendingCandidates();
		const answer = await peer.createAnswer();
		await peer.setLocalDescription(answer);
		sendSignalMessage("answer", answer);
		isInCall.value = true;
		pendingOffer.value = null;
	}

	// 拒绝来电
	async function rejectIncomingCall() {
		if (isClosing) return;
		isClosing = true;
		incomingCallVisible.value = false;
		pendingOffer.value = null;
		sendSignalMessage("reject_call", {});
		closeCurrentWindow();
	}

	// 处理对方的 answer
	async function handleAnswer(answer) {
		if (isProcessingAnswer) return;
		isProcessingAnswer = true;
		try {
			if (!peer || peer.remoteDescription) return;
			if (!["have-local-offer", "stable"].includes(peer.signalingState)) return;
			if (peer.signalingState === "stable") return;
			await peer.setRemoteDescription(new RTCSessionDescription(answer));
			await flushPendingCandidates();
			answerReceived.value = true;
		} catch (error) {
			console.error("[VoiceChat] 处理 answer 失败:", error.message);
		} finally {
			isProcessingAnswer = false;
		}
	}

	// ICE 候选缓冲处理
	async function handleCandidate(candidate) {
		if (!peer || !peer.remoteDescription) {
			pendingIceCandidates.push(candidate);
			return;
		}
		try {
			await peer.addIceCandidate(new RTCIceCandidate(candidate));
		} catch (e) {
			console.warn("[VoiceChat] 添加 ICE 候选失败:", e.message);
		}
	}

	async function flushPendingCandidates() {
		if (!peer || pendingIceCandidates.length === 0) return;
		for (const c of pendingIceCandidates.splice(0)) {
			try {
				await peer.addIceCandidate(new RTCIceCandidate(c));
			} catch (e) {
				console.warn("[VoiceChat] 应用缓冲 ICE 候选失败:", e.message);
			}
		}
	}

	async function handleRejectCall() {
		ElMessage({ message: "对方已拒绝通话", type: "warning", duration: 2000 });
		await sendVoiceCallMessage("reject");
		await endCall(false, 2000);
	}

	async function handleEndCall() {
		await sendVoiceCallMessage(callStartedAt ? "end" : "cancel");
		await endCall(false);
	}

	// 结束通话并关闭窗口
	async function endCall(sendSignal = true, closeDelay = 0) {
		if (isClosing) return;
		isClosing = true;
		const shouldRecord = isCaller.value && sendSignal && isInCall.value;
		if (sendSignal && isInCall.value) sendSignalMessage("end_call", {});
		if (shouldRecord) {
			await sendVoiceCallMessage(callStartedAt ? "end" : "cancel");
		}

		isInCall.value = false;
		answerReceived.value = false;
		isConnected.value = false; // 重置连接状态
		incomingCallVisible.value = false;
		pendingOffer.value = null;

		if (peer) {
			peer.close();
			peer = null;
		}

		if (closeDelay > 0) await new Promise((r) => setTimeout(r, closeDelay));
		closeCurrentWindow();
	}

	function closeCurrentWindow() {
		window.ipcRenderer.send("sendWinControl", { action: "close", type: 0, force: true });
	}

	async function handleWindowBeforeClose() {
		if (incomingCallVisible.value && pendingOffer.value) {
			await rejectIncomingCall();
			return;
		}
		await endCall(true);
	}

	async function sendVoiceCallMessage(event) {
		if (!isCaller.value || hasSentFinalMessage || !targetUserId.value) return;
		hasSentFinalMessage = true;
		await sendCallEventMessage({
			contactId: targetUserId.value,
			sessionId: initData.value?.sessionId,
			recipientType: 0,
			userInfo: {
				userId: currentUserId.value,
				nickName: initData.value?.currentUserName || userInfo.value?.nickName
			},
			messageContent: getCallEventText({
				mediaType: "audio",
				event,
				durationText: formatCallDuration(callStartedAt)
			})
		});
	}

	function toggleAudio() {
		if (localStream) {
			const track = localStream.getAudioTracks()[0];
			if (track) {
				track.enabled = !track.enabled;
				audioEnabled.value = track.enabled;
			}
		}
	}

	const remover = () => {
		window.ipcRenderer.removeAllListeners("pageInitData");
		ipcRenderer.removeAllListeners("voicertc:connection-status");
		ipcRenderer.removeAllListeners("voicertc:signal-message");
		ipcRenderer.removeAllListeners("voicertc:connection-error");
	};

	onMounted(async () => {
		remover();
		window.ipcRenderer.on("pageInitData", async (e, data) => {
			initData.value = data;
			targetUserId.value = data.recipient;
			currentUserId.value = data.useId;
			isCaller.value = data.isCaller === true;
			callStartedAt = null;
			hasSentFinalMessage = false;
			isClosing = false;
			await syncRemoteProfile(data);

			// 主动发起时自动开始呼叫
			if (data.autoStart) await startCall();
		});

		await getLocalMedia();
		setupIpcListeners();
		window.ipcRenderer.on("call-window:before-close", handleWindowBeforeClose);
	});

	onUnmounted(() => {
		if (localStream) localStream.getTracks().forEach((t) => t.stop());
		if (peer) peer.close();
		window.ipcRenderer.removeListener("call-window:before-close", handleWindowBeforeClose);
		remover();
	});
</script>

<style lang="scss" scoped>
	.voiceChat {
		user-select: none;
		height: 100vh;
		width: 100vw;
		display: flex;
		flex-direction: column;
		background: linear-gradient(180deg, #ffffff 0%, #f7f8fa 100%);
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
			backdrop-filter: blur(14px);
			border-bottom: 1px solid rgba(15, 23, 42, 0.08);

			.title-text {
				color: #111827;
				font-size: 15px;
				font-weight: 600;
				letter-spacing: 0.3px;
			}
		}

		.voice-area {
			flex: 1;
			position: relative;
			display: flex;
			align-items: center;
			justify-content: center;
			padding: 24px;

			.avatar-section {
				display: flex;
				flex-direction: column;
				align-items: center;
				gap: 16px;

				.remote-avatar {
					width: 120px;
					height: 120px;
					border-radius: 50%;
					object-fit: cover;
					border: 5px solid rgba(255, 255, 255, 0.96);
					box-shadow: 0 16px 36px rgba(15, 23, 42, 0.1);
				}

				.remote-avatar.fallback {
					display: flex;
					align-items: center;
					justify-content: center;
					background: linear-gradient(135deg, #e5e7eb 0%, #cbd5e1 100%);
					color: #475569;
					font-size: 42px;
					font-weight: 700;
				}

				.remote-name {
					color: #111827;
					font-size: 22px;
					font-weight: 600;
				}

				.call-status {
					color: #6b7280;
					font-size: 14px;
				}
			}

			.incoming-call-panel {
				position: absolute;
				left: 50%;
				bottom: 24px;
				transform: translateX(-50%);
				z-index: 5;
				min-width: 300px;
				padding: 18px 22px;
				border-radius: 22px;
				background: rgba(255, 255, 255, 0.92);
				backdrop-filter: blur(18px);
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
					color: rgba(47, 95, 69, 0.72);
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
					transition: all 0.2s ease;
				}

				.accept-btn {
					background: linear-gradient(135deg, #2fbe6a 0%, #1fa856 100%);
					color: #fff;
					&:hover {
						transform: translateY(-1px);
						box-shadow: 0 12px 24px rgba(31, 168, 86, 0.22);
					}
				}

				.reject-btn {
					background: #edf2ee;
					color: #4f5f57;
					&:hover {
						background: #e3e9e4;
					}
				}
			}
		}

		/* 用于播放远端音频的隐藏元素（通过 JS 直接操作 srcObject） */
		audio {
			display: none;
		}

		.controls-wrapper {
			padding: 0 18px 18px;
		}

		.controls-panel {
			border-radius: 22px;
			padding: 14px 20px 18px;
			background: rgba(255, 255, 255, 0.96);
			backdrop-filter: blur(20px);
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
				transition: all 0.2s ease;
				flex-shrink: 0;

				&:hover {
					background: #e5e7eb;
					transform: translateY(-1px);
				}

				&.off {
					background: rgba(255, 69, 58, 0.72);
					&:hover {
						background: rgba(255, 69, 58, 0.9);
					}
				}

				&.end-btn {
					width: 62px;
					height: 62px;
					background: linear-gradient(135deg, #ff5f57 0%, #ff3b30 100%);
					box-shadow: 0 10px 20px rgba(255, 69, 58, 0.35);
					color: #fff;
					&:hover {
						background: linear-gradient(135deg, #ff625b 0%, #ff453a 100%);
					}
				}
			}
		}

		:deep(.control-button) {
			.iconfont {
				color: rgba(17, 24, 39, 0.75);
				&:hover {
					background-color: rgba(15, 23, 42, 0.06);
					color: #111827;
				}
			}
			.icon-close {
				&:hover {
					background-color: #ff453a;
					color: #fff;
				}
			}
		}
	}
</style>
