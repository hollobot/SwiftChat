<template>
	<div class="media-window">
		<div class="win-title drag"></div>

		<!-- 顶部工具栏 -->
		<div class="media-op no-drag">
			<!-- 上/下一个（所有类型都支持翻页） -->
			<div
				:class="['iconfont icon-shangyizhang', currentIndex == 0 ? 'not-allow' : '']"
				@dblclick.stop
				title="上一个"
				@click="next(-1)"
			></div>
			<div
				:class="[
					'iconfont icon-xiayizhang',
					currentIndex >= allFileList.length - 1 ? 'not-allow' : ''
				]"
				@dblclick.stop
				title="下一个"
				@click="next(1)"
			></div>

			<!-- 仅图片显示缩放/旋转工具 -->
			<template v-if="fileList[0].fileType == 0">
				<el-divider direction="vertical" />
				<div class="iconfont icon-fangda icon-base" @click.stop="changeSize(0.1)" @dblclick.stop title="放大"></div>
				<div class="iconfont icon-suoxiao icon-base" @click.stop="changeSize(-0.1)" @dblclick.stop title="缩小"></div>
				<el-divider direction="vertical" />
				<div
					:class="['icon-base iconfont', isOnetoOne ? 'icon-yuanshidaxiao' : 'icon-shiyingchuangkou']"
					@click.stop="resize"
					@dblclick.stop
					:title="isOnetoOne ? '图片使用窗口大小' : '图片原始大小'"
				></div>
				<div class="icon-base iconfont icon-zhengxuanzhuan" @click.stop="rotate" @dblclick.stop title="旋转"></div>
				<el-divider direction="vertical" />
			</template>

			<!-- 文本文件显示字体大小调整 -->
			<template v-if="fileList[0].fileType == 4">
				<el-divider direction="vertical" />
				<div class="iconfont icon-fangda icon-base" @click.stop="fontSize = Math.min(fontSize + 1, 24)" title="增大字号"></div>
				<div class="iconfont icon-suoxiao icon-base" @click.stop="fontSize = Math.max(fontSize - 1, 10)" title="缩小字号"></div>
				<el-divider direction="vertical" />
			</template>

			<!-- 所有类型都支持另存为 -->
			<div class="icon-base iconfont icon-xiazai" @click.stop="downloadFile" @dblclick.stop title="另存为..."></div>
		</div>

		<!-- 内容区域 -->
		<div class="media-panel">
			<!-- 图片（v-viewer） -->
			<Viewer
				v-if="fileList[0].fileType == 0 && fileList[0].status == 1"
				:options="options"
				@inited="inited"
				:images="fileList"
			>
				<img v-show="false" :src="fileList[0].url" />
			</Viewer>

			<!-- 视频（DPlayer） -->
			<div
				ref="player"
				id="player"
				v-show="fileList[0].fileType == 1 && fileList[0].status == 1"
				style="width: 100%; height: 100%"
			></div>

			<!-- 普通文件（只显示信息和下载入口） -->
			<div v-if="fileList[0].fileType == 2" class="file-panel">
				<div class="file-panel-icon">
					<svg viewBox="0 0 24 24" width="64" height="64">
						<path fill="#3182ce" d="M14,2H6C4.9,2,4,2.9,4,4v16c0,1.1,0.9,2,2,2h12c1.1,0,2-0.9,2-2V8L14,2z M16,18H8v-2h8V18z M16,14H8v-2h8V14z M13,9V3.5L18.5,9H13z"/>
					</svg>
				</div>
				<div class="file-item name">{{ fileList[0].fileName }}</div>
				<div class="file-item size">{{ fileList[0].fileSize }}</div>
				<div class="file-item download" @click="downloadFile">另存为...</div>
			</div>

			<!-- PDF 预览（iframe，Electron 内置 Chromium 支持 PDF 渲染） -->
			<iframe
				v-if="fileList[0].fileType == 3 && fileList[0].status == 1"
				:src="fileList[0].url"
				class="pdf-viewer"
				frameborder="0"
			></iframe>

			<!-- 文本/代码预览 -->
			<div
				v-if="fileList[0].fileType == 4 && fileList[0].status == 1"
				class="text-viewer"
			>
				<div class="text-header">
					<span class="text-filename">{{ fileList[0].fileName }}</span>
					<span class="text-lines">{{ lineCount }} 行</span>
				</div>
				<pre class="text-content" :style="{ fontSize: fontSize + 'px' }">{{ fileList[0].textContent }}</pre>
			</div>

			<!-- 文本超限提示 -->
			<div v-if="fileList[0].fileType == 4 && fileList[0].tooLarge" class="too-large-tip">
				文件过大，仅展示前 500 KB 内容
			</div>

			<!-- 加载中 -->
			<div v-if="fileList[0].status == 0" class="loading">加载中...</div>
		</div>

		<WindowControlButton @close-callback="closeWin"></WindowControlButton>
	</div>
</template>

<script setup>
	import WindowControlButton from "@/components/windowControlButton.vue";
	import { onMounted, onUnmounted, ref, computed } from "vue";
	import "viewerjs/dist/viewer.css";
	import { component as Viewer } from "v-viewer";
	import Dplayer from "dplayer";
	import { getLocalItem } from "@/utils/storage";

	const currentIndex = ref(0);
	const allFileList = ref([]);
	const fileList = ref([{ fileType: 0, status: 0 }]);
	const fontSize = ref(13); // 文本预览字体大小（px），可通过工具栏调整

	// 当前文本内容的行数（用于状态栏显示）
	const lineCount = computed(() => {
		const text = fileList.value[0]?.textContent || "";
		return text ? text.split("\n").length : 0;
	});

	const options = ref({
		inline: true,
		toolbar: false,
		navbar: false,
		button: false,
		title: false,
		zoomRatio: 0.1,
		zoomOnWheel: false,
		mousewheel: false
	});

	const viewerMy = ref(null);
	const inited = (e) => {
		viewerMy.value = e;
	};

	// 切换文件（上/下一个）
	const next = (step) => {
		const target = currentIndex.value + step;
		if (target < 0 || target > allFileList.value.length - 1) return;
		currentIndex.value = target;
		getCurrentFile();
	};

	// 图片放大/缩小
	const changeSize = (zoomRatio) => {
		viewerMy.value.zoom(zoomRatio, true);
	};

	// 图片旋转
	const rotate = () => {
		viewerMy.value.rotate(90, true);
	};

	// 图片适应窗口 / 原始大小切换
	const isOnetoOne = ref(false);
	const resize = () => {
		isOnetoOne.value = !isOnetoOne.value;
		if (!isOnetoOne.value) {
			viewerMy.value.zoomTo(viewerMy.value.initialImageData.ratio, true);
		} else {
			viewerMy.value.zoomTo(1, true);
		}
	};

	// 滚轮缩放（仅图片）
	const onWheel = (e) => {
		if (fileList.value[0].fileType !== 0) return;
		changeSize(e.deltaY < 0 ? 0.1 : -0.1);
	};

	// 窗口关闭前暂停视频（修复：仅在视频存在时调用 pause，避免 closeWin 报错）
	const closeWin = () => {
		if (dplayer.value) dplayer.value.pause();
	};

	// 另存为
	const downloadFile = () => {
		const curFile = allFileList.value[currentIndex.value];
		window.ipcRenderer.send("downloadFile", {
			partType: curFile.partType,
			fileId: curFile.fileId
		});
	};

	// 构建文件的本地服务器 URL
	const getUrl = (curFile) => {
		const port = getLocalItem("fileServerProt");
		return `http://127.0.0.1:${port}/file?fileId=${curFile.fileId}&partType=${curFile.partType}&fileType=${curFile.fileType}&showCover=false&forceGet=${curFile.forceGet}&_t=${Date.now()}`;
	};

	// 文本内容最大读取字节数（500 KB），防止大文件卡死
	const TEXT_MAX_BYTES = 512 * 1024;

	// 切换到当前文件，按类型分别处理
	const getCurrentFile = async () => {
		if (dplayer.value) dplayer.value.pause();

		const curFile = allFileList.value[currentIndex.value];
		const url = getUrl(curFile);

		// 先置为 loading 状态
		fileList.value.splice(0, 1, {
			url,
			fileType: curFile.fileType,
			status: 0,
			fileName: curFile.fileName,
			fileSize: curFile.fileSize,
			textContent: "",
			tooLarge: false
		});

		if (curFile.fileType == 1) {
			// 视频：切换播放源
			fileList.value[0].status = 1;
			dplayer.value.switchVideo({ url });
		} else if (curFile.fileType == 4) {
			// 文本/代码：异步 fetch 文件内容
			try {
				const resp = await fetch(url);
				const buffer = await resp.arrayBuffer();
				const tooLarge = buffer.byteLength > TEXT_MAX_BYTES;
				// 超出限制时只取前 TEXT_MAX_BYTES 字节
				const slice = tooLarge ? buffer.slice(0, TEXT_MAX_BYTES) : buffer;
				const text = new TextDecoder("utf-8").decode(slice);
				fileList.value.splice(0, 1, {
					url,
					fileType: curFile.fileType,
					status: 1,
					fileName: curFile.fileName,
					fileSize: curFile.fileSize,
					textContent: text,
					tooLarge
				});
			} catch (e) {
				fileList.value.splice(0, 1, {
					url,
					fileType: curFile.fileType,
					status: 1,
					fileName: curFile.fileName,
					fileSize: curFile.fileSize,
					textContent: `加载失败：${e.message}`,
					tooLarge: false
				});
			}
		} else {
			// 图片(0)、PDF(3)、普通文件(2)：直接置为就绪
			fileList.value[0].status = 1;
		}
	};

	const player = ref();
	const dplayer = ref();
	const initPlayer = () => {
		dplayer.value = new Dplayer({
			element: player.value,
			theme: "#b7daff",
			screenshot: true,
			video: { url: "" }
		});
	};

	const remover = () => {
		window.ipcRenderer.removeAllListeners("pageInitData");
	};

	onMounted(() => {
		remover();
		initPlayer();
		window.addEventListener("wheel", onWheel);

		window.ipcRenderer.on("pageInitData", (e, data) => {
			const { fileList: list, currentFileId } = data;
			allFileList.value = list;
			if (list.length == 1) {
				currentIndex.value = 0;
			} else {
				const idx = list.findIndex((item) => item.fileId == currentFileId);
				currentIndex.value = idx;
			}
			getCurrentFile();
		});
	});

	onUnmounted(() => {
		window.ipcRenderer.removeAllListeners("pageInitData");
		window.removeEventListener("wheel", onWheel);
	});
</script>

<style lang="scss" scoped>
	.media-window {
		height: 100vh;
		background: #fff;
		z-index: 100;
		display: flex;
		flex-direction: column;

		.win-title {
			height: 25px;
			flex-shrink: 0;
		}

		.media-op {
			z-index: 101;
			position: absolute;
			top: 0;
			left: 5px;
			display: flex;
			align-items: center;

			.iconfont {
				opacity: 0.6;
				font-size: 12px;
				color: black;
				padding: 5px 8px;
				cursor: pointer;
				&:hover {
					background-color: #ddd;
				}
			}

			.icon-base {
				font-size: 14px;
			}

			.not-allow {
				opacity: 0.1 !important;
				cursor: not-allowed;
				&:hover {
					background-color: #fff;
				}
			}
		}

		.media-panel {
			flex: 1;
			overflow: hidden;
			display: flex;
			justify-content: center;
			align-items: center;
			position: relative;

			:deep(.viewer-backdrop) {
				background: #f5f5f5;
			}
		}
	}

	/* PDF 预览：撑满整个内容区 */
	.pdf-viewer {
		width: 100%;
		height: 100%;
		border: none;
		display: block;
	}

	/* 文本/代码预览区 */
	.text-viewer {
		width: 100%;
		height: 100%;
		display: flex;
		flex-direction: column;
		overflow: hidden;
		background: #fafafa;

		.text-header {
			display: flex;
			align-items: center;
			justify-content: space-between;
			padding: 6px 16px;
			background: #f0f0f0;
			border-bottom: 1px solid #e0e0e0;
			flex-shrink: 0;

			.text-filename {
				font-size: 13px;
				font-weight: 600;
				color: #333;
			}

			.text-lines {
				font-size: 12px;
				color: #888;
			}
		}

		.text-content {
			flex: 1;
			overflow: auto;
			margin: 0;
			padding: 16px;
			font-family: "Consolas", "JetBrains Mono", "Fira Code", "Courier New", monospace;
			line-height: 1.6;
			color: #24292e;
			white-space: pre;
			word-break: normal;
			background: #fafafa;
			/* 自定义滚动条 */
			&::-webkit-scrollbar {
				width: 6px;
				height: 6px;
			}
			&::-webkit-scrollbar-thumb {
				background: #ccc;
				border-radius: 3px;
			}
		}
	}

	/* 超限提示 */
	.too-large-tip {
		position: absolute;
		bottom: 12px;
		left: 50%;
		transform: translateX(-50%);
		background: rgba(0, 0, 0, 0.55);
		color: #fff;
		font-size: 12px;
		padding: 4px 14px;
		border-radius: 12px;
		pointer-events: none;
	}

	/* 普通文件信息面板 */
	.file-panel {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 12px;
		padding: 40px 20px;

		.file-panel-icon {
			opacity: 0.85;
		}

		.file-item {
			font-size: 14px;
			color: #555;

			&.name {
				font-weight: 600;
				color: #333;
				font-size: 15px;
				max-width: 320px;
				word-break: break-all;
				text-align: center;
			}

			&.size {
				color: #888;
				font-size: 13px;
			}

			&.download {
				background-color: #07c160;
				color: #fff;
				border-radius: 6px;
				padding: 8px 24px;
				cursor: pointer;
				margin-top: 8px;
				transition: opacity 0.15s ease;
				&:hover {
					opacity: 0.85;
				}
			}
		}
	}

	.loading {
		color: #999;
		font-size: 14px;
	}
</style>
