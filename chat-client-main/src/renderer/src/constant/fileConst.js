// 文件后缀 → fileType 数字映射
// 0=图片, 1=视频, 2=普通文件, 3=PDF, 4=文本/代码
export const File_TYPE = {
	// 图片
	jpeg: 0,
	jpg: 0,
	png: 0,
	gif: 0,
	bmp: 0,
	webp: 0,
	// 视频
	mp4: 1,
	avi: 1,
	rmvb: 1,
	mkv: 1,
	mov: 1,
	// PDF
	pdf: 3,
	// 纯文本 / 代码（在预览窗口内直接渲染文本内容）
	txt: 4,
	md: 4,
	json: 4,
	js: 4,
	ts: 4,
	jsx: 4,
	tsx: 4,
	vue: 4,
	py: 4,
	java: 4,
	c: 4,
	cpp: 4,
	cs: 4,
	go: 4,
	rs: 4,
	rb: 4,
	html: 4,
	htm: 4,
	css: 4,
	scss: 4,
	less: 4,
	xml: 4,
	yaml: 4,
	yml: 4,
	toml: 4,
	ini: 4,
	conf: 4,
	sh: 4,
	bash: 4,
	bat: 4,
	sql: 4,
	// 数字 → 中文名
	0: "图片",
	1: "视频",
	2: "文件",
	3: "PDF",
	4: "文本",
	// 中文名 → 数字
	图片: 0,
	视频: 1,
	文件: 2,
	PDF: 3,
	文本: 4
};

export const getFileType = (suffix) => {
	if (!suffix) return 2;
	if (typeof suffix === "string") suffix = suffix.toLowerCase();
	const fileType = File_TYPE[suffix];
	return fileType === undefined ? 2 : fileType;
};

const fileTypeCategory = {
	image: "图片",
	video: "视频"
};

export function getFileCategory(type) {
	return fileTypeCategory[type] || "文件";
}
