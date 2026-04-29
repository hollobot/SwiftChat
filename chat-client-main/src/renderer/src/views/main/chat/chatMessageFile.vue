<template>
	<div class="wechat-file-message">
		<!-- 文件类型图标 -->
		<div class="file-icon">
			<!-- PDF 图标（红色） -->
			<svg v-if="data.fileType == 3" viewBox="0 0 24 24" width="36" height="36">
				<path
					fill="#e53e3e"
					d="M14,2H6C4.9,2,4,2.9,4,4v16c0,1.1,0.9,2,2,2h12c1.1,0,2-0.9,2-2V8L14,2z M13,9V3.5L18.5,9H13z"
				/>
				<text x="6" y="19" font-size="5.5" fill="#fff" font-weight="bold">PDF</text>
			</svg>
			<!-- 文本/代码图标（蓝绿色） -->
			<svg v-else-if="data.fileType == 4" viewBox="0 0 24 24" width="36" height="36">
				<path
					fill="#319795"
					d="M14,2H6C4.9,2,4,2.9,4,4v16c0,1.1,0.9,2,2,2h12c1.1,0,2-0.9,2-2V8L14,2z M13,9V3.5L18.5,9H13z"
				/>
				<path fill="#fff" d="M8,13h2v2H8zm0-3h2v2H8zm4,3h4v2h-4zm0-3h4v2h-4z" />
			</svg>
			<!-- 普通文件图标（蓝色） -->
			<svg v-else viewBox="0 0 24 24" width="36" height="36">
				<path
					fill="#3182ce"
					d="M14,2H6C4.9,2,4,2.9,4,4v16c0,1.1,0.9,2,2,2h12c1.1,0,2-0.9,2-2V8L14,2z M16,18H8v-2h8V18z M16,14H8v-2h8V14z M13,9V3.5L18.5,9H13z"
				/>
			</svg>
		</div>

		<div class="file-info">
			<div class="file-name" :title="data.fileName">{{ data.fileName }}</div>
			<div class="file-meta">
				<!-- 文件类型标签 -->
				<span class="file-type-tag" :class="typeTagClass">{{ typeLabel }}</span>
				<span class="file-size">{{ formatFileSize(data.fileSize) }}</span>
			</div>
		</div>

		<div class="file-action">
			<!-- PDF 和文本显示"预览"，普通文件显示"下载" -->
			<span v-if="data.fileType == 3 || data.fileType == 4" class="action-btn preview-btn">预览</span>
			<!-- 普通文件：阻止冒泡，不触发父级的打开预览窗口，直接 emit 下载 -->
			<span v-else class="action-btn download-btn" @click.stop="emit('download')">下载</span>
		</div>
	</div>
</template>

<script setup>
	import { computed } from "vue";
	import { formatFileSize } from "@/utils/fileUtils";

	const emit = defineEmits(["download"]);

	const props = defineProps({
		data: {
			type: Object,
			default: () => ({})
		}
	});

	// 文件类型标签文字
	const typeLabel = computed(() => {
		const map = { 3: "PDF", 4: "文本" };
		return map[props.data.fileType] || "文件";
	});

	// 标签颜色 class
	const typeTagClass = computed(() => {
		if (props.data.fileType == 3) return "tag-pdf";
		if (props.data.fileType == 4) return "tag-text";
		return "tag-file";
	});
</script>

<style lang="scss" scoped>
	.wechat-file-message {
		display: flex;
		align-items: center;
		padding: 10px 14px;
		background-color: #ffffff;
		border-radius: 8px;
		width: 200px;
		min-height: 60px;
		box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
		gap: 10px;
		cursor: pointer;
		transition: background-color 0.15s ease;

		&:hover {
			background-color: #f0f0f0;
		}
	}

	.file-icon {
		flex-shrink: 0;
		display: flex;
		align-items: center;
	}

	.file-info {
		flex: 1;
		min-width: 0;

		.file-name {
			font-size: 13px;
			color: #333;
			font-weight: 500;
			white-space: nowrap;
			overflow: hidden;
			text-overflow: ellipsis;
			margin-bottom: 4px;
		}

		.file-meta {
			display: flex;
			align-items: center;
			gap: 6px;
		}

		.file-type-tag {
			font-size: 10px;
			padding: 1px 5px;
			border-radius: 3px;
			font-weight: 600;
			flex-shrink: 0;

			&.tag-pdf {
				background: #fff5f5;
				color: #e53e3e;
				border: 1px solid #feb2b2;
			}

			&.tag-text {
				background: #e6fffa;
				color: #319795;
				border: 1px solid #81e6d9;
			}

			&.tag-file {
				background: #ebf8ff;
				color: #3182ce;
				border: 1px solid #bee3f8;
			}
		}

		.file-size {
			font-size: 11px;
			color: #999;
		}
	}

	.file-action {
		flex-shrink: 0;

		.action-btn {
			font-size: 12px;
			padding: 4px 10px;
			border-radius: 4px;
			cursor: pointer;
			transition: opacity 0.15s ease;

			&:hover {
				opacity: 0.8;
			}
		}

		.preview-btn {
			background-color: #07c160;
			color: #fff;
		}

		.download-btn {
			background-color: #3182ce;
			color: #fff;
		}
	}
</style>
