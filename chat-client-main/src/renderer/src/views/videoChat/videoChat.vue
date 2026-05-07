<template>
	<div class="videoChat">
		<!-- 顶部拖拽栏 -->
		<div class="top-bar drag">
			<div class="top-bar-left">
				<span class="title-text">视频通话</span>
			</div>
		</div>

		<!-- 主视频区 -->
		<div class="video-area">
			<div class="video-gradient"></div>
			<video v-show="!showRemotePlaceholder" ref="remoteVideo" class="remote-video" autoplay playsinline></video>

			<div class="avatar-stage" v-if="showRemotePlaceholder">
				<img v-if="remoteProfile.avatar" :src="remoteProfile.avatar" class="stage-avatar" alt="头像" />
				<div v-else class="stage-avatar fallback">{{ displayInitial }}</div>
				<div class="stage-name">{{ displayName }}</div>
			</div>

			<div class="incoming-call-panel" v-if="incomingCallVisible">
				<div class="incoming-title">收到视频通话邀请</div>
				<div class="incoming-desc">{{ displayName }} 正在呼叫你</div>
				<div class="incoming-actions">
					<button class="action-btn accept-btn" @click="acceptIncomingCall">接听</button>
					<button class="action-btn reject-btn" @click="rejectIncomingCall">拒绝</button>
				</div>
			</div>

			<div class="local-wrapper">
				<video v-show="!showLocalPlaceholder" ref="localVideo" class="local-video" autoplay playsinline muted></video>
				<div class="local-tag">我</div>
				<!-- 本地没有可用视频流时，使用头像与昵称占位，避免小窗留白 -->
				<div v-if="showLocalPlaceholder" class="cam-off-overlay">
					<img v-if="currentAvatar" :src="currentAvatar" class="cam-off-avatar" alt="我的头像" />
					<div v-else class="cam-off-avatar fallback">{{ currentInitial }}</div>
					<div class="cam-off-name">{{ currentDisplayName }}</div>
				</div>
			</div>
		</div>

		<!-- 底部控制栏 -->
		<div class="controls-wrapper">
			<div class="controls-panel">
				<div class="controls-hint">{{ controlHint }}</div>
				<div class="controls">
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

					<button
						class="ctrl-btn"
						:class="{ off: !videoEnabled }"
						@click="toggleVideo"
						:title="videoEnabled ? '关闭摄像头' : '开启摄像头'"
					>
						<svg viewBox="0 0 24 24" fill="currentColor" width="22" height="22">
							<path
								d="M17 10.5V7c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l4 4v-11l-4 4z"
							/>
						</svg>
					</button>
				</div>
			</div>
		</div>

		<div class="log-toggle" @click="showLogs = !showLogs">日志</div>
		<div class="logs-panel" v-if="showLogs">
			<h5>消息日志</h5>
			<div class="log-container">
				<div v-for="(log, index) in logs" :key="index" class="log-item">{{ log }}</div>
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

	// 响应式变量
	const localVideo = ref(null);
	const remoteVideo = ref(null);
	const currentUserId = ref("");
	const targetUserId = ref("");
	const logs = ref([]);
	const isInCall = ref(false);
	const answerReceived = ref(false); // 对方已接听但 P2P 尚未 connected
	const isConnected = ref(false); // P2P 连接已成功建立（connectionState === "connected"）
	const incomingCallVisible = ref(false);
	const pendingOffer = ref(null);
	const videoEnabled = ref(true);
	const audioEnabled = ref(true);
	const connectionStatus = ref("disconnected");
	const showLogs = ref(false);
	const hasRemoteStream = ref(false); // 是否已收到远程视频流
	const remoteStream = ref(null);
	const remoteVideoEnabled = ref(true); // 远端摄像头是否开启（由 camera_toggle 信令驱动）
	// localStream 是非响应式变量，用此 ref 驱动本地占位符的响应式更新
	const localVideoReady = ref(false);
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
	const currentAvatar = ref(avatar);

	const hasRemoteVideoTrack = computed(() => {
		return remoteStream.value?.getVideoTracks?.().some((track) => track.readyState === "live") || false;
	});

	const showRemotePlaceholder = computed(() => {
		// 无流、无视频轨、或对方摄像头已关闭时显示占位符
		return !hasRemoteStream.value || !hasRemoteVideoTrack.value || !remoteVideoEnabled.value;
	});

	const showLocalPlaceholder = computed(() => {
		if (!videoEnabled.value) return true;
		// 依赖 localVideoReady 而非非响应式的 localStream，确保获取流成功后能重新渲染
		return !localVideoReady.value;
	});

	const displayName = computed(() => {
		return remoteProfile.value.name || initData.value?.contactName || initData.value?.targetNickName || "好友";
	});

	const displayInitial = computed(() => {
		return displayName.value?.slice(0, 1)?.toUpperCase() || "友";
	});

	// 优先使用 pageInitData 里携带的当前用户昵称，避免独立窗口 Pinia store 未初始化的问题
	const currentDisplayName = computed(() => {
		return initData.value?.currentUserName || userInfo.value?.nickName || "我";
	});

	const currentInitial = computed(() => {
		return currentDisplayName.value?.slice(0, 1)?.toUpperCase() || "我";
	});

	const callStatusText = computed(() => {
		if (incomingCallVisible.value) return "等待你选择接听或拒绝";
		if (isConnected.value) return "通话已建立，当前网络正常";
		if (isInCall.value) return "正在等待对方接听...";
		return "准备发起视频通话";
	});

	const controlHint = computed(() => {
		// 以 P2P 连接状态（而非视频流是否到达）作为"已接通"判断依据，避免流延迟导致提示滞后
		if (isConnected.value) return "你们已经连线成功，可以继续通话";
		if (answerReceived.value) return "对方已接听，正在建立连接...";
		if (isInCall.value) return "正在呼叫中，请保持窗口开启";
		return "准备发起通话";
	});

	let localStream = null;
	let peer = null;
	let isProcessingAnswer = false;
	let callStartedAt = null;
	let hasSentFinalMessage = false;
	let isClosing = false;
	// 在远程描述设置完成前到达的ICE候选缓冲区
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

	const connectionStatusText = computed(() => {
		switch (connectionStatus.value) {
			case "connected":
				return "已连接";
			case "connecting":
				return "连接中...";
			case "disconnected":
				return "未连接";
			case "error":
				return "连接错误";
			default:
				return "未知状态";
		}
	});

	function addLog(message) {
		const timestamp = new Date().toLocaleTimeString();
		logs.value.push(`[${timestamp}] ${message}`);
		console.log(`[WebRTC] ${message}`);
		if (logs.value.length > 50) {
			logs.value.shift();
		}
	}

	async function loadAvatarByEmail(email, setter) {
		if (!email) {
			return;
		}
		const result = await window.ipcRenderer.invoke("getAvatar", email);
		if (result) {
			setter.value = result;
		}
	}

	async function syncRemoteProfile(profile = {}) {
		remoteProfile.value = {
			name: profile.contactName || profile.targetNickName || remoteProfile.value.name || "好友",
			avatar: avatar,
			userId: profile.targetEmail || profile.recipient || remoteProfile.value.userId || ""
		};
		if (remoteProfile.value.userId) {
			// 用 userId 查询头像（getAvatar 仅支持 email，改用 getAvatarByUserId）
			const result = await window.ipcRenderer.invoke("getAvatarByUserId", remoteProfile.value.userId);
			if (result) {
				remoteProfile.value.avatar = result;
			}
		}
	}

	function bindRemoteStream() {
		if (remoteVideo.value && remoteStream.value) {
			remoteVideo.value.srcObject = remoteStream.value;
			// 只有远端存在可用视频轨时才显示大窗视频，否则显示头像占位
			hasRemoteStream.value =
				remoteStream.value?.getVideoTracks?.().some((track) => track.readyState === "live") || false;
		}
	}

	async function createPeerConnection() {
		if (peer) {
			addLog("清理现有peer连接");
			peer.onicecandidate = null;
			peer.ontrack = null;
			peer.onconnectionstatechange = null;
			peer.close();
			peer = null;
		}

		// 新建连接时清空ICE缓冲区，旧会话的候选不应用于新连接
		pendingIceCandidates = [];

		peer = new RTCPeerConnection({
			iceServers: ICE_SERVERS,
			iceCandidatePoolSize: 10,
			iceTransportPolicy: "all",
			bundlePolicy: "max-bundle",
			rtcpMuxPolicy: "require"
		});

		if (localStream) {
			localStream.getTracks().forEach((track) => {
				peer.addTrack(track, localStream);
			});
		}

		peer.ontrack = (event) => {
			addLog("收到远程流");
			remoteStream.value = event.streams[0];
			const remoteVideoTrack = remoteStream.value?.getVideoTracks?.()[0];
			if (remoteVideoTrack) {
				remoteVideoTrack.onmute = () => {
					addLog("远程摄像头已关闭");
					hasRemoteStream.value = false;
				};
				remoteVideoTrack.onunmute = () => {
					addLog("远程摄像头已开启");
					hasRemoteStream.value = true;
					bindRemoteStream();
				};
				remoteVideoTrack.onended = () => {
					addLog("远程视频轨已结束");
					hasRemoteStream.value = false;
				};
			}
			bindRemoteStream();
		};

		peer.onicecandidate = (event) => {
			if (event.candidate) {
				sendSignalMessage("candidate", event.candidate);
			}
		};

		peer.onconnectionstatechange = () => {
			addLog(`P2P连接状态变化: ${peer.connectionState}`);
			if (peer.connectionState === "connected") {
				addLog("P2P连接建立成功！");
				isConnected.value = true;  // 连接成功，切换提示为"已连线"
				answerReceived.value = false; // 清除中间态
				if (!callStartedAt) callStartedAt = Date.now();
			} else if (peer.connectionState === "failed") {
				addLog("P2P连接失败，尝试重新连接");
				isConnected.value = false;
			} else if (peer.connectionState === "disconnected") {
				addLog("P2P连接断开");
				isConnected.value = false;
				if (isCaller.value) sendVideoCallMessage("end");
				endCall(false);
			}
		};

		peer.oniceconnectionstatechange = () => {
			addLog(`ICE连接状态: ${peer.iceConnectionState}`);
		};

		peer.onsignalingstatechange = () => {
			addLog(`信令状态变化: ${peer.signalingState}`);
		};
	}

	async function getLocalMedia() {
		let newStream = null;
		let videoAvailable = true;

		try {
			// 先申请新流，成功后再停止旧流，避免申请失败时留下死轨道
			newStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
		} catch (err) {
			addLog(`视频+音频获取失败(${err.message})，尝试仅获取音频`);
			videoAvailable = false;
			try {
				// 降级：没有摄像头或摄像头被占用时，至少保留麦克风，确保接收方能发送声音
				newStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
			} catch (audioErr) {
				addLog(`音频获取也失败: ${audioErr.message}`);
				return false;
			}
		}

		// 停止旧流（在新流就绪后再停止，避免中间空档）
		if (localStream) {
			localStream.getTracks().forEach((track) => track.stop());
		}
		localStream = newStream;

		if (localVideo.value && videoAvailable) {
			localVideo.value.srcObject = localStream;
		}

		// 同步视频状态
		const hasLive =
			localStream?.getVideoTracks?.().some((track) => track.enabled && track.readyState === "live") || false;
		videoEnabled.value = hasLive;
		localVideoReady.value = hasLive;

		// 同步音频状态：新流的音频轨默认开启，确保与 UI 一致
		const hasAudio = localStream?.getAudioTracks?.().some((t) => t.readyState === "live") || false;
		audioEnabled.value = hasAudio;

		addLog(`获取本地媒体流成功（视频: ${videoAvailable}, 音频: ${hasAudio}）`);
		return true;
	}

	async function startCall() {
		if (!currentUserId.value || !targetUserId.value) {
			addLog("用户ID未就绪，无法发起呼叫");
			return;
		}

		addLog(`开始呼叫用户: ${targetUserId.value}`);

		try {
			isInCall.value = false;
			answerReceived.value = false;

			// 若任意轨道仍存活则直接复用，否则重新获取（同时处理无摄像头的降级场景）
			const hasLiveMedia = localStream && localStream.getTracks().some((t) => t.readyState === "live");
			if (!hasLiveMedia) {
				const mediaOk = await getLocalMedia();
				if (!mediaOk) {
					addLog("获取媒体流失败，无法发起呼叫");
					await sendVideoCallMessage("cancel");
					return;
				}
			}

			await createPeerConnection();

			if (peer.signalingState !== "stable") {
				addLog("等待peer连接稳定...");
				await new Promise((resolve) => setTimeout(resolve, 100));
			}

			const offer = await peer.createOffer({
				offerToReceiveAudio: true,
				offerToReceiveVideo: true
			});
			await peer.setLocalDescription(offer);
			addLog(`创建offer后的状态: ${peer.signalingState}`);

			sendSignalMessage("offer", offer);
			isInCall.value = true;
			addLog("呼叫已发送，等待应答...");
		} catch (error) {
			addLog(`开始呼叫失败: ${error.message}`);
			await sendVideoCallMessage("cancel");
			isInCall.value = false;
			if (peer) {
				peer.close();
				peer = null;
			}
		}
	}

	function sendSignalMessage(signalType, signalData) {
		const message = {
			sendUserId: currentUserId.value,
			receiveUserId: targetUserId.value,
			signalType: signalType,
			signalData: JSON.stringify(signalData)
		};
		addLog(`准备发送信令: ${signalType} -> ${targetUserId.value}`);
		window.ipcRenderer.send("webrtc:send-signal", message);
		addLog(`发送信令: ${signalType}`);
		return true;
	}

	function setupIpcListeners() {
		addLog("设置 IPC 事件监听器");
		window.ipcRenderer.on("webrtc:connection-status", (event, status) => {
			connectionStatus.value = status;
			switch (status) {
				case "connected":
					addLog("信令服务器连接成功");
					break;
				case "connecting":
					addLog("正在连接信令服务器...");
					break;
				case "disconnected":
					addLog("服务器断开 连接失败。");
					break;
				case "error":
					addLog("信令服务器连接错误");
					break;
			}
		});

		ipcRenderer.on("webrtc:signal-message", async (event, message) => {
			addLog(`收到信令: ${message.signalType}`);
			await handleSignalMessage(message);
		});

		ipcRenderer.on("webrtc:connection-error", (event, error) => {
			addLog(`连接错误: ${error}`);
			connectionStatus.value = "error";
		});
	}

	let flog = true;
	async function handleSignalMessage(message) {
		const { signalType, signalData, sendUserId, sendUserNickName } = message;

		if (signalType == "notOnline" && flog) {
			flog = false;
			await notOnline();
			await sendVideoCallMessage("cancel");
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
				case "camera_toggle":
					// 对方切换摄像头，更新远端占位符显示状态
					remoteVideoEnabled.value = data.enabled;
					addLog(`对方摄像头已${data.enabled ? "开启" : "关闭"}`);
					if (data.enabled) {
						bindRemoteStream(); // 重新绑定流，确保视频恢复显示
					}
					break;
				default:
					addLog(`未知信令类型: ${signalType}`);
			}
		} catch (error) {
			addLog(`处理信令数据失败-${signalType}: ${error.message}`);
		}
	}

	async function handleOffer(offer, sendUserId, sendUserNickName) {
		try {
			addLog(`收到来自 ${sendUserId} 的呼叫`);

			if (isInCall.value) {
				addLog("当前正在通话中，拒绝新的呼叫");
				return;
			}

			targetUserId.value = sendUserId;
			// 来电时需要立即把对方身份同步到大窗占位，避免沿用旧窗口数据
			await syncRemoteProfile({
				contactName: sendUserNickName,
				targetEmail: sendUserId,
				recipient: sendUserId
			});
			pendingOffer.value = offer;
			incomingCallVisible.value = true;
			addLog("等待用户选择接听或拒绝");
		} catch (error) {
			addLog(`处理offer失败: ${error.message}`);
			incomingCallVisible.value = false;
			pendingOffer.value = null;
			isInCall.value = false;
		}
	}

	async function acceptIncomingCall() {
		if (!pendingOffer.value) {
			return;
		}
		incomingCallVisible.value = false;
		try {
			// 检查任意有效轨道是否存在（包括纯音频降级场景），只要有 live 轨道就复用，否则重新获取
			const hasLiveMedia = localStream && localStream.getTracks().some((t) => t.readyState === "live");
			if (!hasLiveMedia) {
				await getLocalMedia();
			}

			if (peer && peer.connectionState !== "closed") {
				peer.close();
				peer = null;
				await new Promise((resolve) => setTimeout(resolve, 100));
			}

			await createPeerConnection();
			await peer.setRemoteDescription(new RTCSessionDescription(pendingOffer.value));
			await flushPendingCandidates();
			const answer = await peer.createAnswer();
			await peer.setLocalDescription(answer);
			sendSignalMessage("answer", answer);
			isInCall.value = true;
			pendingOffer.value = null;
			addLog("已接受呼叫并发送应答");
		} catch (error) {
			addLog(`接听呼叫失败: ${error.message}`);
			pendingOffer.value = null;
			isInCall.value = false;
		}
	}

	async function rejectIncomingCall() {
		if (isClosing) return;
		isClosing = true;
		incomingCallVisible.value = false;
		pendingOffer.value = null;
		// 拒绝来电后通知发起方同步关闭窗口
		addLog("准备发送拒绝信令");
		sendSignalMessage("reject_call", {});
		addLog("已拒绝来电");
		closeCurrentWindow();
	}

	async function handleAnswer(answer) {
		if (isProcessingAnswer) {
			addLog("正在处理answer，跳过重复消息");
			return;
		}
		isProcessingAnswer = true;

		try {
			if (!peer) {
				addLog("无效的peer连接");
				return;
			}

			addLog(`处理answer时的详细状态:
            - signalingState: ${peer.signalingState}
            - connectionState: ${peer.connectionState}
            - iceConnectionState: ${peer.iceConnectionState}
            - localDescription: ${peer.localDescription ? "已设置" : "未设置"}
            - remoteDescription: ${peer.remoteDescription ? "已设置" : "未设置"}`);

			if (peer.remoteDescription) {
				addLog("警告: 远程描述已存在，跳过重复设置");
				return;
			}

			const validStates = ["have-local-offer", "stable"];
			if (!validStates.includes(peer.signalingState)) {
				let waitCount = 0;
				const maxWait = 30;
				addLog(`等待状态稳定，当前: ${peer.signalingState}`);
				while (
					!validStates.includes(peer.signalingState) &&
					peer.signalingState !== "closed" &&
					waitCount < maxWait
				) {
					await new Promise((resolve) => setTimeout(resolve, 100));
					waitCount++;
					if (waitCount % 10 === 0) {
						addLog(`等待状态中... ${waitCount / 10}s, 当前: ${peer.signalingState}`);
					}
				}
				if (!validStates.includes(peer.signalingState)) {
					addLog(`状态等待超时，最终状态: ${peer.signalingState}`);
					return;
				}
			}

			if (peer.signalingState === "stable") {
				addLog("连接已经稳定，无需处理answer");
				return;
			}

			if (!answer || !answer.type || !answer.sdp) {
				addLog("无效的answer格式");
				return;
			}

			await peer.setRemoteDescription(new RTCSessionDescription(answer));
			// 远程描述就绪，立即应用此前缓冲的ICE候选
			await flushPendingCandidates();
			answerReceived.value = true; // 对方已接听，进入连接中间态
			addLog(`远程应答描述设置成功，新状态: ${peer.signalingState}`);
		} catch (error) {
			addLog(`处理answer失败: ${error.message}`);
			console.error("HandleAnswer error:", error);
			if (error.name === "InvalidStateError") {
				addLog("检测到状态错误，可能需要重新建立连接");
			}
		} finally {
			isProcessingAnswer = false;
		}
	}

	// ICE候选缓冲：远程描述未就绪时先缓存，待setRemoteDescription后统一应用
	async function handleCandidate(candidate) {
		try {
			if (!peer || !peer.remoteDescription) {
				pendingIceCandidates.push(candidate);
				addLog("ICE候选已缓冲（等待远程描述就绪）");
				return;
			}
			await peer.addIceCandidate(new RTCIceCandidate(candidate));
			addLog("添加ICE候选成功");
		} catch (error) {
			addLog(`添加ICE候选失败: ${error.message}`);
		}
	}

	// 将缓冲区内的ICE候选全部应用到当前peer连接，在setRemoteDescription后立即调用
	async function flushPendingCandidates() {
		if (!peer || pendingIceCandidates.length === 0) return;
		addLog(`应用 ${pendingIceCandidates.length} 个缓冲ICE候选`);
		for (const c of pendingIceCandidates.splice(0)) {
			try {
				await peer.addIceCandidate(new RTCIceCandidate(c));
			} catch (e) {
				addLog(`应用缓冲ICE候选失败: ${e.message}`);
			}
		}
	}

	async function handleRejectCall() {
		addLog("对方已拒绝通话");
		ElMessage({ message: "对方已拒绝通话", type: "warning", duration: 2000 });
		await sendVideoCallMessage("reject");
		await endCall(false);
	}

	async function handleEndCall() {
		addLog("对方结束了通话");
		await sendVideoCallMessage(callStartedAt ? "end" : "cancel");
		await endCall(false);
	}

	// closeDelay: 关窗前的延迟毫秒数，用于拒绝场景让用户看清提示
	async function endCall(sendSignal = true, closeDelay = 0) {
		if (isClosing) return;
		isClosing = true;
		const shouldRecord = isCaller.value && sendSignal && isInCall.value;
		if (sendSignal && isInCall.value) {
			sendSignalMessage("end_call", {});
		}
		if (shouldRecord) {
			await sendVideoCallMessage(callStartedAt ? "end" : "cancel");
		}

		isInCall.value = false;
		answerReceived.value = false;
		isConnected.value = false; // 重置连接状态
		incomingCallVisible.value = false;
		pendingOffer.value = null;
		hasRemoteStream.value = false;
		remoteStream.value = null;
		remoteVideoEnabled.value = true; // 重置远端摄像头状态，避免下次通话复用旧状态

		if (peer) {
			peer.close();
			peer = null;
		}

		if (remoteVideo.value) {
			remoteVideo.value.srcObject = null;
		}

		addLog("通话已结束");
		if (closeDelay > 0) {
			await new Promise((resolve) => setTimeout(resolve, closeDelay));
		}
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

	async function sendVideoCallMessage(event) {
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
				mediaType: "video",
				event,
				durationText: formatCallDuration(callStartedAt)
			})
		});
	}

	async function notOnline() {
		isInCall.value = false;
		ElMessage({ message: "对方可能不在线" });
	}

	function toggleVideo() {
		if (localStream) {
			const videoTrack = localStream.getVideoTracks()[0];
			if (videoTrack) {
				videoTrack.enabled = !videoTrack.enabled;
				videoEnabled.value = videoTrack.enabled;
				addLog(`摄像头${videoEnabled.value ? "开启" : "关闭"}`);
				// 通知对方摄像头状态，让对方能正确显示/隐藏占位符
				if (isInCall.value) {
					sendSignalMessage("camera_toggle", { enabled: videoEnabled.value });
				}
			}
		}
	}

	function toggleAudio() {
		if (localStream) {
			const audioTrack = localStream.getAudioTracks()[0];
			if (audioTrack) {
				audioTrack.enabled = !audioTrack.enabled;
				audioEnabled.value = audioTrack.enabled;
				addLog(`麦克风${audioEnabled.value ? "开启" : "静音"}`);
			}
		}
	}

	const remover = () => {
		window.ipcRenderer.removeAllListeners("pageInitData");
		ipcRenderer.removeAllListeners("webrtc:connection-status");
		ipcRenderer.removeAllListeners("webrtc:signal-message");
		ipcRenderer.removeAllListeners("webrtc:connection-error");
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

			// pageInitData 到达后才能获取到 useId，在此加载本地用户头像
			const result = await window.ipcRenderer.invoke("getAvatarByUserId", data.useId);
			if (result) currentAvatar.value = result;

			bindRemoteStream();
			// 主动发起呼叫时自动开始通话
			if (data.autoStart) {
				await startCall();
			}
		});

		await getLocalMedia();
		bindRemoteStream();
		setupIpcListeners();
		window.ipcRenderer.on("call-window:before-close", handleWindowBeforeClose);
	});

	onUnmounted(() => {
		if (localStream) {
			localStream.getTracks().forEach((track) => track.stop());
		}
		if (peer) {
			peer.close();
		}
		window.ipcRenderer.removeListener("call-window:before-close", handleWindowBeforeClose);
		remover();
	});
</script>

<style lang="scss" scoped>
	.videoChat {
		user-select: none;
		height: 100vh;
		width: 100vw;
		display: flex;
		flex-direction: column;
		background: linear-gradient(180deg, #ffffff 0%, #f7f8fa 100%);
		box-sizing: border-box;
		overflow: hidden;

		/* 顶部拖拽栏 */
		.top-bar {
			height: 52px;
			flex-shrink: 0;
			display: flex;
			align-items: center;
			justify-content: flex-start;
			padding: 0 52px 0 18px;
			-webkit-app-region: drag;
			background: rgba(255, 255, 255, 0.94);
			backdrop-filter: blur(14px);
			border-bottom: 1px solid rgba(15, 23, 42, 0.08);

			.top-bar-left {
				display: flex;
				align-items: center;
			}

			.title-text {
				color: #111827;
				font-size: 15px;
				font-weight: 600;
				letter-spacing: 0.3px;
			}

			.status-text {
				color: rgba(36, 82, 58, 0.68);
				font-size: 12px;
			}

			.status-dot {
				width: 10px;
				height: 10px;
				border-radius: 50%;
				flex-shrink: 0;
				box-shadow: 0 0 12px rgba(255, 255, 255, 0.18);
				&.connected {
					background: #30d158;
				}
				&.connecting {
					background: #ffd60a;
					animation: pulse 1.5s ease-in-out infinite;
				}
				&.disconnected,
				&.error {
					background: #ff453a;
				}
			}
		}

		.video-area {
			flex: 1;
			position: relative;
			overflow: hidden;
			padding: 18px 18px 0;

			.video-gradient {
				position: absolute;
				inset: 0;
				background: linear-gradient(180deg, rgba(255, 255, 255, 0) 0%, rgba(15, 23, 42, 0.04) 100%);
				pointer-events: none;
				z-index: 1;
			}

			.remote-video {
				width: 100%;
				height: 100%;
				object-fit: cover;
				background: #f3f4f6;
				display: block;
				border-radius: 24px;
				box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.04);
			}

			.call-info-card {
				display: none;
			}

			.avatar-stage {
				position: absolute;
				inset: 0;
				z-index: 2;
				display: flex;
				flex-direction: column;
				align-items: center;
				justify-content: center;
				gap: 18px;
				padding: 40px;

				.stage-avatar {
					width: 168px;
					height: 168px;
					border-radius: 50%;
					object-fit: cover;
					border: 6px solid rgba(255, 255, 255, 0.96);
					box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);
				}

				.stage-avatar.fallback {
					display: flex;
					align-items: center;
					justify-content: center;
					background: linear-gradient(135deg, #e5e7eb 0%, #cbd5e1 100%);
					color: #475569;
					font-size: 52px;
					font-weight: 700;
				}

				.stage-name {
					color: #111827;
					font-size: 24px;
					font-weight: 600;
				}
			}

			.incoming-call-panel {
				position: absolute;
				left: 50%;
				bottom: 36px;
				transform: translateX(-50%);
				z-index: 5;
				min-width: 320px;
				padding: 18px 22px;
				border-radius: 22px;
				background: rgba(255, 255, 255, 0.88);
				backdrop-filter: blur(18px);
				box-shadow: 0 18px 40px rgba(15, 23, 42, 0.1);
				border: 1px solid rgba(15, 23, 42, 0.08);
				text-align: center;

				.incoming-title {
					font-size: 18px;
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

			.placeholder {
				display: none;
			}

			.local-wrapper {
				position: absolute;
				bottom: 28px;
				right: 28px;
				z-index: 3;
				width: 182px;
				height: 128px;
				border-radius: 18px;
				overflow: hidden;
				box-shadow: 0 12px 30px rgba(62, 121, 84, 0.18);
				border: 1px solid rgba(255, 255, 255, 0.45);
				background: rgba(255, 255, 255, 0.72);

				.local-video {
					width: 100%;
					height: 100%;
					object-fit: cover;
					background: #d8eadf;
					display: block;
				}

				.local-tag {
					position: absolute;
					top: 10px;
					left: 10px;
					padding: 2px 8px;
					border-radius: 10px;
					background: rgba(36, 82, 58, 0.56);
					color: rgba(255, 255, 255, 0.92);
					font-size: 12px;
				}

				.cam-off-overlay {
					position: absolute;
					inset: 0;
					background: rgba(226, 243, 233, 0.98);
					display: flex;
					flex-direction: column;
					align-items: center;
					justify-content: center;
					gap: 8px;
					padding: 10px;

					.cam-off-avatar {
						width: 68px;
						height: 68px;
						border-radius: 50%;
						object-fit: cover;
						border: 2px solid rgba(255, 255, 255, 0.8);
						box-shadow: 0 8px 18px rgba(80, 135, 101, 0.12);
					}

					.cam-off-avatar.fallback {
						display: flex;
						align-items: center;
						justify-content: center;
						background: linear-gradient(135deg, #07c160 0%, #48cf8b 100%);
						color: #fff;
						font-size: 22px;
						font-weight: 700;
					}

					.cam-off-name {
						max-width: 100%;
						font-size: 12px;
						font-weight: 600;
						color: #111827;
						white-space: nowrap;
						overflow: hidden;
						text-overflow: ellipsis;
					}
				}
			}
		}

		.controls-wrapper {
			padding: 0 18px 18px;
			background: linear-gradient(180deg, rgba(255, 255, 255, 0) 0%, rgba(15, 23, 42, 0.03) 100%);
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
					&:hover {
						background: linear-gradient(135deg, #ff625b 0%, #ff453a 100%);
					}
				}
			}
		}

		.log-toggle {
			position: absolute;
			bottom: 128px;
			right: 20px;
			z-index: 6;
			min-width: 48px;
			height: 30px;
			padding: 0 10px;
			border-radius: 16px;
			background: #f8fafc;
			display: flex;
			justify-content: center;
			align-items: center;
			cursor: pointer;
			font-size: 12px;
			color: #374151;
			backdrop-filter: blur(10px);
			transition: background 0.2s;
			&:hover {
				background: #ffffff;
			}
		}

		.logs-panel {
			user-select: text;
			position: absolute;
			bottom: 166px;
			right: 20px;
			width: 320px;
			max-height: 220px;
			background: rgba(255, 255, 255, 0.98);
			border-radius: 16px;
			padding: 12px;
			font-size: 11px;
			display: flex;
			flex-direction: column;
			backdrop-filter: blur(14px);
			border: 1px solid rgba(15, 23, 42, 0.08);
			box-shadow: 0 12px 30px rgba(15, 23, 42, 0.08);
			z-index: 50;

			h5 {
				margin: 0 0 8px;
				font-size: 12px;
				font-weight: 600;
				color: #111827;
			}

			.log-container {
				flex: 1;
				overflow-y: auto;
				.log-item {
					margin: 2px 0;
					color: rgba(67, 7, 245, 0.72);
					word-break: break-all;
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

	@keyframes pulse {
		0%,
		100% {
			opacity: 1;
		}
		50% {
			opacity: 0.35;
		}
	}
</style>
