# APK 下载页设计规格

**日期**: 2026-06-01
**页面名称**: AppDownload
**路由路径**: `/download`
**状态**: 待用户评审

---

## 1. 背景与目标

手机端微信扫码后，在手机浏览器中打开的 APK 下载引导页。面向 Android 用户下载 APK、iOS 用户跳转 App Store。

**核心路径**:
```
用户微信扫码 → 打开手机浏览器 → 加载下载页 → Android 下载 APK / iOS 跳转 App Store
```

---

## 2. 布局结构

| 区域 | 内容 |
|---|---|
| 页面背景 | 浅青渐变 (#f0f9ff → #e0f2fe) |
| 主卡片 | 居中白色圆角卡片，宽 92vw，最大 400px |
| App Logo | 顶部居中，72×72 圆角方形 |
| App 名称 | 滔滔 |
| 版本号 | App 名称下方，灰色小字 |
| 功能简介 | 2-3 行功能描述文字 |
| 下载按钮区 | Android 主按钮 + iOS 次按钮，垂直堆叠 |
| 微信引导 | 下载按钮下方提示文案 |
| 页面底部 | 署名"Powered by daren" |

---

## 3. 视觉规范

### 颜色

| 用途 | 色值 |
|---|---|
| 页面背景渐变起点 | #f0f9ff |
| 页面背景渐变终点 | #e0f2fe |
| 卡片背景 | #ffffff |
| 主按钮渐变起点 | #0891b2 |
| 主按钮渐变终点 | #06b6d4 |
| 主按钮文字 | #ffffff |
| 次按钮文字/边框 | #0891b2 |
| 次按钮背景 | #ffffff |
| 版本号/辅助文字 | #64748b |
| 页面底部文字 | #94a3b8 |

### 字体

- App 名称: 24px, font-weight 600
- 版本号: 12px, color #64748b
- 功能简介: 14px, color #475569, line-height 1.6
- 按钮文字: 15px, font-weight 600
- 微信引导: 12px, color #94a3b8

### 间距

- 卡片内边距: 40px 32px
- 卡片圆角: 16px
- 卡片阴影: 0 4px 24px rgba(0,0,0,0.06)
- Logo 与名称间距: 20px
- 名称与版本号间距: 6px
- 版本号与简介间距: 20px
- 简介与按钮区间距: 28px
- 两按钮间距: 12px

### 动效

- 卡片: slideUp 动画，0.8s ease，延迟 0.15s
- 页面: fadeIn 动画，0.6s ease

---

## 4. 组件规范

### AppDownload.vue

**Props**: 无

**Emits**: 无

**State**:
- `apkUrl`: 固定 APK 下载地址（写死在组件内）
- `iosUrl`: App Store URL（写死在组件内）

**按钮行为**:
- Android 按钮: `<a href={apkUrl} download>` 触发浏览器下载
- iOS 按钮: `window.open(iosUrl, '_blank')` 跳转 App Store

### 微信引导提示

文案: `"微信内如无法下载，请点击右上角··· → 用浏览器打开"`

位置: 下载按钮区下方，文字居中

---

## 5. 响应式

| 屏幕宽度 | 卡片宽度 | 按钮布局 |
|---|---|---|
| < 480px | 92vw | 垂直堆叠 |
| ≥ 480px | 最大 400px | 并排（flex row） |

---

## 6. 路由配置

```js
{
  path: '/download',
  name: 'AppDownload',
  component: () => import('@/views/AppDownload.vue'),
  meta: {
    title: '下载滔滔',
    requiresAuth: false,
  },
}
```

---

## 7. 后续可扩展项（当前版本不实现）

- [ ] 后端接口返回 APK URL 和 App Store URL，支持动态配置
- [ ] App 截图展示
- [ ] 版本更新日志
- [ ] 下载量/评分展示
- [ ] APK URL 有效性校验
