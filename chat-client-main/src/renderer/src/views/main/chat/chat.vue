<template>
	<layout>
		<!-- 搜索 -->
		<template #contact-search>
			<div class="contact-search">
				<!-- keyup 键盘触发 -->
				<el-input
					v-model="searchInput"
					placeholder="搜索"
					clearable
					size="small"
					@keyup="searchContact"
				>
					<template #prefix>
						<span :class="['iconfont', 'icon-weixinsousuoicon']"></span>
					</template>
				</el-input>
				<div
					:class="['contact-add', 'iconfont', 'icon-faqiqunliao']"
					@click="searchContact"
				></div>
			</div>
		</template>

		<!-- 会话列表 -->
		<template #data-list>
			<el-scrollbar>
				<template v-if="!searchInput">
					<chatSession
						v-for="sessionInfo in chatSessionList"
						:key="sessionInfo.sessionId"
						:session-info="sessionInfo"
						:current-session="currentChatSession?.sessionId == sessionInfo.sessionId"
						@contextmenu.stop="onContextMenu(sessionInfo, $event)"
						@click="chatSessionListHandler(sessionInfo)"
					></chatSession>
				</template>
				<template v-if="searchInput">
					<SearchSession
						v-for="sessionInfo in searchList"
						:key="sessionInfo.sessionId"
						:session-info="sessionInfo"
						@clear-search="searchInput = null"
					></SearchSession>
				</template>
			</el-scrollbar>
		</template>

		<!-- 标题 -->
		<template #right-drag>
			<div v-if="currentChatSession" class="title">
				<!-- 单聊群聊标题 -->
				<div class="title-name">
					{{ currentChatSession.contactName }}
					{{
						currentChatSession.memberCount != undefined &&
						currentChatSession.memberCount != 0
							? "(" + currentChatSession.memberCount + ")"
							: ""
					}}
				</div>
				<div
					v-if="currentChatSession.contactType == 1 && groupInfoInfo"
					class="no-drag iconfont icon-sangediandian"
					@click="showGroupDetail(currentChatSession)"
				></div>
			</div>
		</template>

		<!-- 聊天页面 -->
		<template #right-no-drag>
			<template v-if="currentChatSession">
				<el-scrollbar
					ref="scrollbarRef"
					class="scrollbar-message"
					:class="{ hidden: !isShowScrollbar }"
					@scroll="handleScroll"
				>
					<!-- 顶部加载动画 -->
					<div
						v-show="isLoadingHistory"
						class="history-loading iconfont icon-jiazai"
					></div>
					<template v-for="item in renderedTimeMessages" :key="item.messageId">
						<!-- 展示聊天消息时间 -->
						<ChatMessageTime v-show="item.showTime" :data="item"></ChatMessageTime>
						<!--
						1://添加好友成功
						3://群创建成功
						8://解散群聊
						9://好友加入群组
						11://退出群聊
						12://退出群聊(踢出)
						-->
						<template
							v-if="
								item.messageType == 1 ||
								item.messageType == 3 ||
								item.messageType == 8 ||
								item.messageType == 9 ||
								item.messageType == 10 ||
								item.messageType == 11 ||
								item.messageType == 12 ||
								item.messageType == 18
							"
						>
							<ChatMessageSys :data="item"></ChatMessageSys>
						</template>
						<template
							v-if="
								item.messageType == 1 ||
								item.messageType == 2 ||
								item.messageType == 5
							"
						>
							<ChatMessage
								:data="item"
								:current-chat-session="currentChatSession"
								@show-media-detail="showMediaDetailHandler"
							></ChatMessage>
						</template>
					</template>
				</el-scrollbar>
				<div class="message-send">
					<MessageSend
						:current-chat-session="currentChatSession"
						:disable-group-voice-call="isGroupVoiceButtonDisabled"
						:disable-group-video-call="isGroupVideoButtonDisabled"
						@send-message-local="sendMessageLocalHandler"
						@start-video-call="startVideoCall"
						@start-voice-call="startVoiceCall"
					></MessageSend>
				</div>
			</template>
			<template v-else>
				<blank></blank>
			</template>
		</template>
	</layout>
	<ChatGroupDetail
		ref="chatGropDetailRef"
		@del-session="delGroupSession"
		@change-group-info="changeGroupInfo"
	></ChatGroupDetail>
	<el-dialog v-model="groupVoiceDialogVisible" :title="groupCallDialogTitle" width="560px">
		<div class="group-call-member-list" v-loading="groupVoiceLoading">
			<el-checkbox-group v-model="selectedGroupVoiceUserIds">
				<div v-for="member in groupVoiceMemberList" :key="member.id" class="group-call-member">
					<el-checkbox :label="member.id">
						<div class="member-option">
							<ShowLocalImage
								:width="30"
								:height="30"
								:file-id="member.id"
								part-type="avatar"
								:file-type="0"
							></ShowLocalImage>
							<span class="member-name">{{ member.name }}</span>
						</div>
					</el-checkbox>
				</div>
			</el-checkbox-group>
		</div>
		<template #footer>
			<el-button @click="groupVoiceDialogVisible = false">取消</el-button>
			<el-button type="primary" @click="confirmGroupVoiceCall">发起通话</el-button>
		</template>
	</el-dialog>
</template>

<script setup>
	import { onMounted, ref, nextTick, computed, onUnmounted, watch } from "vue";
	import layout from "@/components/layout.vue";
	import ChatMessageTime from "./chatMessageTime.vue";
	import ChatMessageSys from "./chatMessageSys.vue";
	import ChatGroupDetail from "./chatGroupDetail.vue";
	import MessageSend from "./messageSend.vue";
	import ChatMessage from "@/views/main/chat/chatMessage.vue";
	import blank from "@/components/blank.vue";
	import ShowLocalImage from "@/components/showLocalImage.vue";
	import SearchSession from "./searchSession.vue";
	import chatSession from "./chatSession.vue";
	import "@imengyu/vue3-context-menu/lib/vue3-context-menu.css";
	import ContextMenu from "@imengyu/vue3-context-menu";
	import { ElMessage, ElMessageBox } from "element-plus";
	import { useAvatarUpdateStore } from "@/stores/avatarUpdateStore";
	const avatarUpdateStore = useAvatarUpdateStore();
	import { useRoute } from "vue-router";
	const route = useRoute();
	import { storeToRefs } from "pinia";
	import { useUserInfoStore } from "@/stores/userInfoStore";
	const userInfoStore = useUserInfoStore();
	import { useContactStore } from "@/stores/contactStore";
	const contactStore = useContactStore();
	const { userInfo } = storeToRefs(userInfoStore);
	import { useMessageCountStore } from "@/stores/messageCountStore";
	const messageCountStore = useMessageCountStore();
	import { selectGroup } from "@/api/groupContactApi";

	// 哪些消息需要铃声提示
	const audioMsgType = [1, 2, 4, 5, 7, 8, 9, 10, 11, 12, 14];

	import msgAudio from "@/assets/media/消息铃声.mp3";
	let audio = new Audio(msgAudio);

	// 消息滑动窗口
	const scrollbarRef = ref();
	// 会话列表
	const chatSessionList = ref([]);

	// 当前会话消息列表
	const messageList = ref([]);

	// 选中会话信息
	const currentChatSession = ref(null);
	const groupVoiceDialogVisible = ref(false);
	const groupVoiceLoading = ref(false);
	const groupVoiceMemberList = ref([]);
	const selectedGroupVoiceUserIds = ref([]);
	const groupCallMediaType = ref("audio");
	const activeGroupCallState = ref(null);
	let pendingGroupCallActionMediaType = null;
	const GROUP_CALL_LABEL = {
		audio: "语音通话",
		video: "视频通话"
	};
	const groupCallDialogTitle = computed(() => {
		return groupCallMediaType.value === "video" ? "选择群视频成员" : "选择群语音成员";
	});
	const activeGroupCallMediaType = computed(() => {
		return activeGroupCallState.value?.active ? activeGroupCallState.value.mediaType || "audio" : null;
	});
	// 已有群视频时禁用群语音发起按钮，避免同群出现第二条通话渠道。
	const isGroupVoiceButtonDisabled = computed(() => {
		return (
			currentChatSession.value?.contactType == 1 &&
			activeGroupCallMediaType.value === "video"
		);
	});
	// 已有群语音时禁用群视频发起按钮，避免同群出现第二条通话渠道。
	const isGroupVideoButtonDisabled = computed(() => {
		return (
			currentChatSession.value?.contactType == 1 &&
			activeGroupCallMediaType.value === "audio"
		);
	});

	// 消息分页配置信息
	let messagePagingInfo = {
		totalPage: 0,
		pageNo: 1,
		maxMessageId: null,
		noData: false
	};

	const searchInput = ref(null); // 搜索输入框
	const searchList = ref([]); // 搜索结果列表
	const searchContact = () => {
		// 这里可以添加搜索逻辑
		searchList.value = chatSessionList.value
			.map((session) => {
				const matchName = session.contactName.includes(searchInput.value);
				const matchLastMessage = session.lastMessage?.includes(searchInput.value);

				if (matchName || matchLastMessage) {
					// 创建新对象，添加高亮字段
					return {
						...session,

						// 添加高亮的属性
						contactName: matchName
							? highlightText(session.contactName, searchInput.value)
							: session.contactName,
						lastMessage: matchLastMessage
							? highlightText(session.lastMessage, searchInput.value)
							: session.lastMessage
					};
				}
				return null; // 不匹配的返回 null
			})
			.filter(Boolean); // 过滤掉 null 值
	};

	// 添加高亮文本的方法
	const highlightText = (text, keyword) => {
		if (!keyword) return text;
		const reg = new RegExp(keyword, "gi");
		return text.replace(reg, (match) => `<span class="highlight">${match}</span>`);
	};

	/**
	 * 发送数据保存到主进程store里面
	 */
	const setCurrentChatSession = ({ contactId, sessionId }) => {
		window.ipcRenderer.send("setCurrentSession", { contactId, sessionId });
	};

	/**
	 * 点击用户会话
	 * @param sessionInfo
	 */
	const chatSessionListHandler = (sessionInfo) => {
		messagePagingInfo = { totalPage: 0, pageNo: 0, maxMessageId: null, noData: false };
		currentChatSession.value = sessionInfo;
		// 清空选中消息数的未读数
		messageCountStore.setCount("chatCount", -sessionInfo.noReadCount, false);
		sessionInfo.noReadCount = 0;
		messageList.value = [];
		// 发送查询当前会话消息
		loadChatMessage(sessionInfo.userId, sessionInfo.contactType);
		// 保存当前选中会话到主进程
		setCurrentChatSession(sessionInfo);
	};

	/**
	 * 发送获取消息事件
	 */
	const loadChatMessage = (userId, contactType) => {
		if (messagePagingInfo.noData) {
			return;
		}
		messagePagingInfo.pageNo++;
		window.ipcRenderer.send("loadChatMessage", {
			userId,
			contactType,
			sessionId: currentChatSession.value.sessionId,
			pageNo: messagePagingInfo.pageNo,
			maxMessageId: messagePagingInfo.maxMessageId
		});
	};

	/**
	 * 发送获取消息事件回调
	 */
	const onLoadChatMessage = () => {
		window.ipcRenderer.on("loadChatMessageCallback", async (e, result) => {
			const dataList = result.messageList;
			const pageNo = result.pageNo;
			const pageTotal = result.pageTotal;
			// 最后一页时
			if (pageNo == pageTotal) {
				messagePagingInfo.noData = true;
			}

			// 优化数据合并方式（保持响应式引用）
			messageList.value.unshift(...dataList); // 新数据插入到列表前面
			messagePagingInfo.pageNo = pageNo;
			messagePagingInfo.pageTotal = pageTotal;

			// 如果是第一页时，需要重新确定maxMessageId
			if (pageNo == 1) {
				messagePagingInfo.maxMessageId =
					messageList.value.length <= 0
						? null
						: messageList.value[messageList.value.length - 1].messageId;
				//  滚动条滚动到最底部
				setScrollToTop();
			} else {
				// 等待 DOM 更新
				await nextTick();
				// 添加消息后的高度
				const wrap = scrollbarRef.value?.wrapRef;
				const newScrollHeight = wrap?.scrollHeight;
				const delta = newScrollHeight - oldScrollHeight;

				// 4. 设置新的 scrollTop，保持视觉不变
				scrollbarRef.value?.setScrollTop(delta);
			}
		});
	};

	/**
	 * 设置置顶会话
	 * @param data
	 */
	const setTop = (data) => {
		data.topType = data.topType == 0 ? 1 : 0;
		// 会话排序
		sortUserSession(chatSessionList.value);
		// 发送置顶事件
		window.ipcRenderer.send("topChatSession", {
			contactId: data.contactId,
			topType: data.topType
		});
	};

	/**
	 * 删除会话
	 * @param contactId
	 */
	const delSession = (contactId) => {
		window.ipcRenderer.send("delChatSession", contactId);
		filterSessionList(contactId);
		// 重置聊天消息界面
	};

	/**
	 * 过滤掉删除的会话
	 */
	const filterSessionList = (contactId) => {
		setTimeout(() => {
			chatSessionList.value = chatSessionList.value.filter((items) => {
				return items.contactId != contactId;
			});
		}, 100);
	};

	const onContextMenu = (data, e) => {
		ContextMenu.showContextMenu({
			// 坐标位置
			x: e.x,
			y: e.y,
			items: [
				{
					label: data.topType == 0 ? "置顶" : "取消置顶",
					onClick: () => {
						setTop(data);
					}
				},
				{
					label: "删除聊天",
					onClick: () => {
						ElMessageBox.confirm(`确定要删除聊天吗？`, {
							confirmButtonText: "删除",
							cancelButtonText: "取消",
							type: "warning"
						}).then(() => {
							delSession(data.contactId);
							ElMessage({
								type: "success",
								message: "删除成功"
							});
						});
					}
				}
			]
		});
	};

	/**
	 * 发送获取会话列表事件
	 */
	const localSession = () => {
		window.ipcRenderer.send("localSessionData");
	};

	const pendingContactId = ref(null); // 保存待处理的contactId

	const getSessionKey = (session) => {
		return `${session.sessionId}_${session.userId}`;
	};
	/**
	 * 接收初始化会话列表
	 */
	const onLocalSessionDataCallback = () => {
		window.ipcRenderer.on("localSessionDataCallback", (e, sessionList) => {
			// 会话列表在“初始化刷新”和“收到新消息”时都必须使用同一主键，避免昵称头像串到别的会话
			const sessionMap = new Map();

			chatSessionList.value.forEach((session) => {
				sessionMap.set(getSessionKey(session), session);
			});

			sessionList.forEach((session) => {
				sessionMap.set(getSessionKey(session), session);
			});

			chatSessionList.value = Array.from(sessionMap.values());

			sortUserSession(chatSessionList.value);

			const totalNoRead = chatSessionList.value.reduce((sum, item) => {
				return sum + (item.noReadCount || 0);
			}, 0);
			messageCountStore.setCount("chatCount", totalNoRead, true);

			if (pendingContactId.value) {
				toSendMessage(pendingContactId.value);
				pendingContactId.value = null;
			}
		});
	};

	/**
	 * 会话列表排序
	 * @param dataList
	 */
	const sortUserSession = (dataList) => {
		dataList.sort((a, b) => {
			const topTypeResult = b["topType"] - a["topType"];
			if (topTypeResult == 0) {
				return b["lastReceiveTime"] - a["lastReceiveTime"];
			}
			return topTypeResult;
		});
	};

	/**
	 * 接收消息
	 */
	const groupInfoInfo = ref(true);
	const onReciveMessage = () => {
		window.ipcRenderer.on("reciveMessage", async (e, message) => {
			// 响铃
			if (audioMsgType.includes(message.messageType)) {
				playRing();
			}

			// 好友申请
			if (message.messageType == 4) {
				if (route.query && route.query.title == "新的朋友") {
					window.ipcRenderer.send("clearNoReadCount", {
						userId: userInfo.value.userId,
						type: "user"
					});
					messageCountStore.setCount("contactCount", 0, false);
					return;
				}
				messageCountStore.setCount("contactCount", 1, false);
				return;
			}

			// 群聊申请
			if (message.messageType == 14) {
				if (route.query && route.query.title == "群聊通知") {
					window.ipcRenderer.send("clearNoReadCount", {
						userId: userInfo.value.userId,
						type: "group"
					});
					messageCountStore.setCount("groupCount", 0, false);
					return;
				}
				messageCountStore.setCount("groupCount", 1, false);
				return;
			}

			// 如果是媒体文件接收数据类型
			if (message.messageType == 6) {
				const messageInfo = messageList.value.find((item) => {
					if (item.uuid == message.uuid) {
						return item;
					}
				});
				messageInfo.status = 1;
				return;
			}

			// 强制下线
			if (message.messageType == 7) {
				ElMessage.error("您已被管理员下线");
				setTimeout(() => {
					window.ipcRenderer.send("reLogin");
				}, 1000);
				return;
			}

			// 联系人添加成功后跟新store
			switch (message.messageType) {
				case 1: //添加好友消息
					contactStore.selectUserList(userInfo.value.userId);
					break;
				case 9: // 好友加入群组
					contactStore.selectGroupList(userInfo.value.userId);
					break;
			}

			// 更新群昵称
			if (message.messageType == 10) {
				avatarUpdateStore.triggerUpdate(message.recipientId);
			}

			// 好友改名：更新会话列表中该联系人的显示名称，并刷新联系人 store
			if (message.messageType == 16) {
				chatSessionList.value.forEach((session) => {
					if (session.contactId === message.sendUserId) {
						session.contactName = message.contactName;
					}
				});
				contactStore.selectUserList(userInfo.value.userId);
				return;
			}

			const session = message.extendData;
			let curSession = chatSessionList.value.find((item) => {
				return item.sessionId == message.sessionId && item.userId == message.userId;
			});

			// 跟新渲染session
			if (!curSession) {
				chatSessionList.value.push(session);
			} else {
				// 将 session 对象拷贝到 curSession
				Object.assign(curSession, session);
			}

			// 排序
			sortUserSession(chatSessionList.value);

			// （处理消息）判断是否选中当前会话
			if (
				currentChatSession.value &&
				currentChatSession.value.sessionId == session.sessionId
			) {
				messageList.value.push(message);
				scrollToTop(); //接受消息回滚到最底部
				Object.assign(currentChatSession.value, session); //跟新会话信息
				if (message.messageType == 18 && currentChatSession.value.contactType == 1) {
					refreshCurrentGroupCallState();
				}
			} else {
				// 未选择会话需要提示气泡和未读消息
				switch (message.messageType) {
					case 1: //添加好友消息
					case 2: // 文本消息
					case 3: // 群组已经创建
					case 5: // 媒体消息
					case 8: //群聊解散
					case 9: // 好友加入群组
					case 10: // 群聊信息跟新
					case 11: // 退出了群聊
					case 12: // 被管理员移出了群聊
					case 13: // 添加好友成功消息
					case 18: // 群通话系统消息
						messageCountStore.setCount("chatCount", 1, false);
						break;
				}
			}
		});
	};

	const onAddLocalMessageCallback = () => {
		window.ipcRenderer.on("addLocalMessageCallback", (e, { status, uuid }) => {
			const messageInfo = messageList.value.find((item) => {
				if (item.uuid == uuid) {
					return item;
				}
			});
			if (messageInfo) {
				messageInfo.status = status;
			}
		});
	};

	const playRing = () => {
		audio.currentTime = 0; // 每次从头播放
		audio.play().catch((err) => {
			console.log("播放失败：", err);
		});
	};

	// 发送消息后更新自己的消息列表和会话
	const sendMessageLocalHandler = (messageObj) => {
		messageList.value.push(messageObj);
		const sessionInfo = chatSessionList.value.find((item) => {
			return item.sessionId == messageObj.sessionId && item.userId == messageObj.userId;
		});
		sessionInfo.lastMessage = messageObj.messageContent;
		sessionInfo.lastReceiveTime = messageObj.sendTime;
		sortUserSession(chatSessionList.value);
		// 回滚到最底部
		scrollToTop();
	};

	let scrollToBottomLen = 0;
	// 滑动到最底部
	const scrollToTop = () => {
		if (scrollToBottomLen > 200) {
			return;
		}
		nextTick(() => {
			scrollbarRef.value?.scrollTo({
				top: scrollbarRef.value?.wrapRef.scrollHeight, // 滚动到最底部
				behavior: "smooth" // 平滑滚动
			});
		});
	};

	const isShowScrollbar = ref(false);
	// 设置到最低部
	const setScrollToTop = () => {
		isShowScrollbar.value = false;
		// 再延迟一帧，等所有内容真正渲染出来
		setTimeout(() => {
			const wrap = scrollbarRef.value?.wrapRef;
			if (wrap) {
				scrollbarRef.value?.setScrollTop(wrap.scrollHeight);
			}
			isShowScrollbar.value = true;
		}, 50); // 可以设置 50ms 看看差异
	};

	// 打开媒体消息预览窗口
	const showMediaDetailHandler = (messageUUid) => {
		// 判断点击的是图片/视频(fileType 0/1)还是文件(fileType 2/3/4)，分组翻页
		const clicked = messageList.value.find((item) => item.uuid == messageUUid);
		const isMedia = clicked?.fileType != null && clicked.fileType <= 1;
		let showFileList = messageList.value.filter((item) => {
			if (item.messageType != 5) return false;
			return isMedia ? item.fileType <= 1 : item.fileType >= 2;
		});
		// map()返回一个新的数组
		showFileList = showFileList.map((item) => {
			return {
				partType: "chat",
				fileId: item.uuid,
				fileType: item.fileType,
				fileName: item.fileName,
				fileSize: item.fileSize,
				forceGet: false
			};
		});

		window.ipcRenderer.send("newWindow", {
			windowId: "media",
			title: "图片查看",
			path: "/showMedai",
			data: {
				currentFileId: messageUUid,
				fileList: showFileList
			}
		});
	};

	const isLoadingHistory = ref(false);
	var oldScrollHeight = 0; // 整体高度
	// 记录加载开始时间
	const handleScroll = ({ scrollTop }) => {
		const wrap = scrollbarRef.value?.wrapRef;
		scrollToBottomLen = wrap?.scrollHeight - wrap?.clientHeight - scrollTop;
		if (
			scrollTop == 0 &&
			!isLoadingHistory.value &&
			!messagePagingInfo.noData &&
			messageList.value.length != 0
		) {
			oldScrollHeight = wrap?.scrollHeight;
			isLoadingHistory.value = true;
			// 等 500ms 展示动画
			setTimeout(() => {
				const wrap = scrollbarRef.value?.wrapRef;
				oldScrollHeight = wrap?.scrollHeight;
				// 这里发起加载历史消息
				loadChatMessage(
					currentChatSession.value.userId,
					currentChatSession.value.contactType
				);
				// 动画结束
				isLoadingHistory.value = false;
			}, 50);
		}
	};

	// 计算添加时间消息
	const renderedTimeMessages = computed(() => {
		return messageList.value.map((item, index, list) => {
			const showTime =
				index >= 1 &&
				item.sendTime - list[index - 1].sendTime > 300000 &&
				(item.messageType === 2 || item.messageType === 5);
			return { ...item, showTime };
		});
	});

	// 发起视频通话
	const startVideoCall = async () => {
		if (currentChatSession.value.contactType == 1) {
			queryActiveGroupCall("video");
			return;
		}
		window.ipcRenderer.send("newWindow", {
			windowId: "videoChat",
			title: "视频通话",
			path: "/videoChat",
			data: {
				useId: userInfo.value.userId,
				currentUserName: userInfo.value.nickName,
				recipient: currentChatSession.value.contactId,
				sessionId: currentChatSession.value.sessionId,
				contactName: currentChatSession.value.contactName,
				targetEmail: currentChatSession.value.contactId,
				isCaller: true,
				autoStart: true
			}
		});
	};

	const onLocalCallMessage = () => {
		window.ipcRenderer.on("localCallMessage", (e, message) => {
			const session = message.extendData;
			if (!session) return;

			let curSession = chatSessionList.value.find((item) => {
				return item.sessionId == session.sessionId && item.userId == session.userId;
			});
			if (!curSession) {
				chatSessionList.value.push(session);
			} else {
				Object.assign(curSession, session);
			}
			sortUserSession(chatSessionList.value);

			if (
				currentChatSession.value &&
				currentChatSession.value.sessionId == session.sessionId
			) {
				messageList.value.push(message);
				Object.assign(currentChatSession.value, session);
				if (message.messageType == 18 && currentChatSession.value.contactType == 1) {
					// 通话结束系统消息到达后，主动查询一次群通话状态，刷新按钮状态。
					refreshCurrentGroupCallState();
				}
				scrollToTop();
			}
		});
	};

	// 发起语音通话
	const startVoiceCall = async () => {
		if (currentChatSession.value.contactType == 1) {
			queryActiveGroupCall("audio");
			return;
		}
		window.ipcRenderer.send("newWindow", {
			windowId: "voiceChat",
			title: "语音通话",
			path: "/voiceChat",
			data: {
				useId: userInfo.value.userId,
				currentUserName: userInfo.value.nickName,
				recipient: currentChatSession.value.contactId,
				sessionId: currentChatSession.value.sessionId,
				contactName: currentChatSession.value.contactName,
				targetEmail: currentChatSession.value.contactId,
				isCaller: true,
				autoStart: true
			}
		});
	};

	const showGroupVoiceMemberDialog = async (mediaType = "audio") => {
		groupCallMediaType.value = mediaType;
		groupVoiceDialogVisible.value = true;
		groupVoiceLoading.value = true;
		try {
			const result = await selectGroup(
				currentChatSession.value.contactId,
				userInfo.value.userId
			);
			groupVoiceMemberList.value = (result.data || []).filter((member) => {
				return member.id !== userInfo.value.userId;
			});
			selectedGroupVoiceUserIds.value = groupVoiceMemberList.value.map((member) => member.id);
			if (groupVoiceMemberList.value.length === 0) {
				ElMessage.warning("当前群聊没有可邀请的其他成员");
			}
		} finally {
			groupVoiceLoading.value = false;
		}
	};

	const confirmGroupVoiceCall = async () => {
		if (selectedGroupVoiceUserIds.value.length === 0) {
			ElMessage.warning("请选择群通话成员");
			return;
		}

		const selectedMembers = groupVoiceMemberList.value.filter((member) => {
			return selectedGroupVoiceUserIds.value.includes(member.id);
		});
		const participants = [
			{
				id: userInfo.value.userId,
				name: userInfo.value.nickName,
				status: "self"
			},
			...selectedMembers.map((member) => ({
				id: member.id,
				name: member.name,
				status: "inviting"
			}))
		];
		const isVideoCall = groupCallMediaType.value === "video";
		groupVoiceDialogVisible.value = false;
		window.ipcRenderer.send("newWindow", {
			windowId: "groupVoiceChat",
			title: isVideoCall ? "群视频通话" : "群语音通话",
			path: "/groupVoiceChat",
			width: isVideoCall ? 860 : 680,
			height: isVideoCall ? 680 : 620,
			data: {
				useId: userInfo.value.userId,
				currentUserName: userInfo.value.nickName,
				recipient: currentChatSession.value.contactId,
				groupId: currentChatSession.value.contactId,
				groupName: currentChatSession.value.contactName,
				sessionId: currentChatSession.value.sessionId,
				callId: crypto.randomUUID(),
				mediaType: groupCallMediaType.value,
				participants,
				isCaller: true,
				autoStart: true,
				incoming: false
			}
		});
	};

	const sendGroupCallStateQuery = () => {
		if (!currentChatSession.value || currentChatSession.value.contactType != 1) return;
		window.ipcRenderer.send("groupvoicertc:send-signal", {
			sendUserId: userInfo.value.userId,
			signalType: "query_group_call",
			signalData: "{}",
			messageType: 17,
			callMode: "group",
			mediaType: "audio",
			groupId: currentChatSession.value.contactId,
			groupName: currentChatSession.value.contactName
		});
	};

	const queryActiveGroupCall = (actionMediaType = null) => {
		pendingGroupCallActionMediaType = actionMediaType;
		sendGroupCallStateQuery();
	};

	const refreshCurrentGroupCallState = () => {
		sendGroupCallStateQuery();
	};

	const openExistingGroupCallWindow = (callState) => {
		const isVideoCall = callState.mediaType === "video";
		window.ipcRenderer.send("newWindow", {
			windowId: "groupVoiceChat",
			title: isVideoCall ? "群视频通话" : "群语音通话",
			path: "/groupVoiceChat",
			width: isVideoCall ? 860 : 680,
			height: isVideoCall ? 680 : 620,
			data: {
				useId: userInfo.value.userId,
				currentUserName: userInfo.value.nickName,
				recipient: currentChatSession.value.contactId,
				groupId: callState.groupId || currentChatSession.value.contactId,
				groupName: callState.groupName || currentChatSession.value.contactName,
				sessionId: currentChatSession.value.sessionId,
				callId: callState.callId,
				mediaType: callState.mediaType || "audio",
				participants: callState.participants || [],
				isCaller: false,
				autoStart: false,
				directJoin: true,
				incoming: false,
				callStartedAt: callState.callStartedAt
			}
		});
	};

	const handleGroupCallQueryResponse = async (callState) => {
		const actionMediaType = pendingGroupCallActionMediaType;
		pendingGroupCallActionMediaType = null;
		if (!actionMediaType) return;

		if (!callState.active) {
			await showGroupVoiceMemberDialog(actionMediaType);
			return;
		}

		const activeMediaType = callState.mediaType || "audio";
		const activeLabel = GROUP_CALL_LABEL[activeMediaType] || GROUP_CALL_LABEL.audio;
		if (actionMediaType !== activeMediaType) {
			ElMessage.warning(`当前群聊正在进行${activeLabel}`);
			return;
		}

		try {
			await ElMessageBox.confirm(
				`当前群聊正在进行${activeLabel}，是否加入？`,
				"群通话提示",
				{
					confirmButtonText: "加入",
					cancelButtonText: "取消",
					type: "info"
				}
			);
			openExistingGroupCallWindow(callState);
		} catch (error) {
			// 用户取消加入时不做处理。
		}
	};

	const parseGroupCallState = (message) => {
		try {
			return JSON.parse(message.signalData || "{}");
		} catch (error) {
			console.error("解析群通话状态失败:", error);
			return {};
		}
	};

	const onGroupCallState = () => {
		window.ipcRenderer.on("groupCallState", async (e, message) => {
			if (!currentChatSession.value || message.groupId !== currentChatSession.value.contactId) {
				return;
			}
			const callState = parseGroupCallState(message);
			activeGroupCallState.value = callState.active ? callState : null;
			await handleGroupCallQueryResponse(callState);
		});
	};

	// 群详情
	const chatGropDetailRef = ref();
	const showGroupDetail = (currentChatSession) => {
		chatGropDetailRef.value.show(currentChatSession.contactId, currentChatSession.sessionId);
	};

	// 群聊退出删除群聊会话
	const delGroupSession = (contactId) => {
		delSession(contactId);
	};

	// 群主在聊天详情里修改群信息后，同步当前会话标题和左侧会话列表。
	const changeGroupInfo = (groupInfo) => {
		chatSessionList.value.forEach((session) => {
			if (session.contactId == groupInfo.groupId) {
				session.contactName = groupInfo.groupName;
			}
		});
		if (currentChatSession.value?.contactId == groupInfo.groupId) {
			currentChatSession.value.contactName = groupInfo.groupName;
		}
	};

	// 定位到发送消息界面
	const toSendMessage = (contactId) => {
		const curSession = chatSessionList.value.find((item) => {
			return item.contactId == contactId;
		});

		if (curSession) {
			// 点击会话
			chatSessionListHandler(curSession);
		} else {
			// 3. 如果没有找到会话,可以发送事件去创建新会话
			window.ipcRenderer.send("createNewSession", contactId);
			// 4. 监听会话创建成功的回调
			window.ipcRenderer.once("createNewSessionCallback", (e, newSession) => {
				if (newSession) {
					// 5. 将新会话添加到会话列表
					chatSessionList.value.push(newSession);
					// 6. 排序会话列表
					sortUserSession(chatSessionList.value);
					// 7. 设置当前会话
					chatSessionListHandler(newSession);
				}
			});
		}
	};

	watch(
		() => route.query,
		(newQuery) => {
			if (newQuery.timeStamp && newQuery.contactId) {
				if (chatSessionList.value.length > 0) {
					// 会话列表已加载，直接处理
					toSendMessage(newQuery.contactId);
				} else {
					// 会话列表未加载，保存待处理
					pendingContactId.value = newQuery.contactId;
				}
			}
		},
		{ immediate: true, deep: true }
	);

	// 切换会话时清空本页记录的群通话状态，避免不同群之间的按钮状态串用。
	watch(
		() => currentChatSession.value?.contactId,
		() => {
			activeGroupCallState.value = null;
			pendingGroupCallActionMediaType = null;
			if (currentChatSession.value?.contactType == 1) {
				refreshCurrentGroupCallState();
			}
		}
	);

	const remover = () => {
		window.ipcRenderer.removeAllListeners("localSessionDataCallback");
		window.ipcRenderer.removeAllListeners("reciveMessage");
		window.ipcRenderer.removeAllListeners("localCallMessage");
		window.ipcRenderer.removeAllListeners("groupCallState");
		window.ipcRenderer.removeAllListeners("loadChatMessageCallback");
		window.ipcRenderer.removeAllListeners("addLocalMessageCallback");
	};

	onMounted(() => {
		remover();
		onLocalSessionDataCallback();
		onReciveMessage();
		onLocalCallMessage();
		onGroupCallState();
		onLoadChatMessage();
		onAddLocalMessageCallback();
		// 获取会话
		localSession();
	});

	onUnmounted(() => {
		window.ipcRenderer.send("setCurrentSession", {});
		currentChatSession.value = null;
		// 清除预备路由
		pendingContactId.value = null;
	});
</script>

<style lang="scss" scoped>
	.contact-search {
		height: 35px;
		display: flex;
		align-items: start;
		padding-left: 10px;
		:deep(.el-input__wrapper) {
			background-color: #e2e2e2;
		}

		.contact-add {
			background-color: #e2e2e2;
			padding: 3px;
			margin: 0 10px;
			border-radius: 3px;
		}
	}

		.title {
			position: relative;
			width: 100%;
			height: 100%;
			display: flex;
			align-items: center;
			border-bottom: #e7e7e7 solid 1px;
			.title-name {
				height: 30px;
				margin-left: 20px;
				font-size: 20px;
				font-weight: 550;
			}
			.iconfont {
				position: absolute;
				right: 10px;
				bottom: 10px;
				opacity: 0.8;
			}
		}


	.scrollbar-message {
		height: calc(100% - 130px);
		.history-loading {
			display: flex;
			justify-content: center;
			align-items: center;
			font-size: 14px;
			height: 14px;
			opacity: 0.6;
		}
	}

	.scrollbar-message.hidden {
		visibility: hidden; /* 隐藏可见性，但保留布局空间 */
	}

	.message-send {
		border-top: #e7e7e7 solid 1px;
		height: 130px;
	}

	.group-call-member-list {
		min-height: 220px;
		max-height: 360px;
		overflow-y: auto;

		.group-call-member {
			height: 42px;
			display: flex;
			align-items: center;

			.member-option {
				display: flex;
				align-items: center;
				gap: 10px;
				min-width: 0;
			}

			.member-name {
				max-width: 360px;
				overflow: hidden;
				text-overflow: ellipsis;
				white-space: nowrap;
			}
		}
	}
</style>
