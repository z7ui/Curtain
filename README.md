# 幕布 (Curtain)

模仿三星幕布 / 游戏助推器触控保护。用一层可调透明度的黑盖住屏幕，
适合 OLED 省电、挂机、防止游戏更新时锁屏。

---

## 一、双轨制：两条后端，自动选

```
CurtainRouter.start()
        │
        ├─ 无障碍服务已开启？ ── 是 ──▶ CurtainAccessibilityService
        │                                CurtainOverlay(TYPE_ACCESSIBILITY_OVERLAY)
        │                                ✅ 可以隐藏状态栏 / 导航栏
        │
        └──────────────────── 否 ──▶ CurtainService（前台服务）
                                        CurtainOverlay(TYPE_APPLICATION_OVERLAY)
                                        ⚠️ 状态栏图标仍会浮在幕布上面
```

主界面底部会直接告诉你当前处于哪个模式。

### 为什么普通悬浮窗一定盖不住状态栏

这不是实现问题，是系统定的。SDK 里 `WindowManager.LayoutParams` 的原文：

> Application overlay windows are displayed above all activity windows
> **but below critical system windows like the status bar or IME.**

而且系统会直接忽略这种窗口请求的系统 UI 可见性变更（`setSystemUiVisibility` 白设）。

AOSP 的 `WindowManagerPolicy` 接口文档还补了一句：只有持有
`INTERNAL_SYSTEM_WINDOW`（**系统签名权限**）的应用，alert 类型窗口才可能被分配到
高于 `TYPE_APPLICATION_OVERLAY` 的层级。普通应用拿不到。

### 为什么改掉了 root 方案

上一版用的是 `su -c "settings put global policy_control immersive.full=*"`。
实测在你的机器上**同样盖不住状态栏**，所以整条 root 路径（`RootUtils`、开关、
`policy_control` 的备份还原）已经全部删掉了。

### 现在赌的是无障碍覆盖层

`TYPE_ACCESSIBILITY_OVERLAY` (2032) 属于"受信任覆盖层"（这点在
`FLAG_NOT_TOUCHABLE` 的文档里写得很明确），只有无障碍服务能创建。窗口参数照抄
FakeStandby：

```java
FLAG_NOT_FOCUSABLE | FLAG_TRANSLUCENT_STATUS | FLAG_TRANSLUCENT_NAVIGATION
  | FLAG_FULLSCREEN | FLAG_NOT_TOUCH_MODAL
  | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS

view.setSystemUiVisibility(
    SYSTEM_UI_FLAG_HIDE_NAVIGATION | SYSTEM_UI_FLAG_FULLSCREEN
  | SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
  | SYSTEM_UI_FLAG_LAYOUT_STABLE | SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
```

**需要你实测**：它到底是"层级高于状态栏"还是"被允许隐藏状态栏"，我在 AOSP 的
`DisplayPolicy.getWindowLayerFromTypeLw()` 里没能核对完整（文件太大抓不全）。
装上后看主界面底部显示的是不是"无障碍覆盖层"，以及状态栏有没有真的消失。

> 如果这条路也不行，剩下的就只有 **Shizuku**（需要 ADB 激活）了。
> 它是非 root 环境下唯一能拿到系统级窗口权限的途径，代价是每次重启要重新激活。

### 方案对比

| 方案 | 覆盖状态栏 | 保持前台应用 | 门槛 |
| :--- | :---: | :---: | :--- |
| 三星原生 | ✅ | ✅ | 系统签名权限，拿不到 |
| **无障碍覆盖层（本工程首选）** | 待实测 | ✅ | 开一次无障碍 |
| **普通悬浮窗（本工程降级）** | ❌ | ✅ | 悬浮窗权限 |
| Root + policy_control | ❌（实测） | ✅ | Root |
| Shizuku | ✅ | ✅ | ADB 激活 |
| Xposed | ✅ | ✅ | Root + LSPosed |
| 沉浸模式 Activity | ✅（隐藏） | ❌ 会切前台 | 无 |

---

## 二、修掉的问题

### 1. 幕布开启时改等级 / 自动纯黑没反应

**原因**：我上一版重写 `CurtainOverlayView` 时**把 `SharedPreferences` 监听器删掉了**。
幕布上的按钮只写了偏好、没有刷新视图；主界面的滑杆改了之后幕布更不会知道。

**为什么非要用监听器**：幕布是**悬浮窗**，不是 Activity。它盖住屏幕时主界面仍处于
resumed 状态，改动偏好不会触发任何回调。

**修法**：在 `CurtainOverlayView` 里重新注册
`OnSharedPreferenceChangeListener`，监听 `level` / `timeout` 两个键。
因为同进程 + 主线程调用，`apply()` 会**同步**回调，所以幕布上的按钮和主界面的滑杆
两边都能实时生效。

### 2. 启动 / 停止按钮不实时刷新，点"停止"反而启动

**原因**：同样的道理。用幕布上的手势把它关掉之后，主界面**不会走 `onResume`**，
所以按钮文字还停在"停止幕布"；但点击时读的是 `CurtainPrefs.isRunning`（已经是
false），于是执行了启动 —— 表现就是"显示停止、点它却启动"。

**修法**：`MainActivity` 也注册 `OnSharedPreferenceChangeListener` 监听 `running` 键，
服务端一改状态按钮立刻跟着变。按钮的显示和执行现在来自同一个来源，不可能再脱节。

### 3. 状态栏方案换成双轨制

见上一节。

---

### 4. 时间和按钮太贴屏幕上下边缘

**原因是我引入的 padding 算错了。** `CurtainOverlay` 为了让幕布盖住圆角和挖孔，
把视图画得比屏幕上下左右各大 **48dp**，再整体往左上偏移 48dp。
于是布局里的 `paddingTop="40dp"` 换算到屏幕坐标其实是 **-8dp** —— 内容跑到屏幕外面去了；
底部 `paddingBottom="36dp"` 同理，变成了屏幕下方 12dp。

**修法**：padding 改为代码设置，并在 `CurtainOverlayView.setExtraEdgeInset()` 里
把超画的 48dp 加回去 —— **上下左右四个方向都要加**（第一版只加了上下，
结果时间被推到屏幕左边外面、电量 pill 跑到右边外面，等于同样的 bug 换了个方向）。
现在实际的**可见**留白：

| 位置 | 相对屏幕边缘 |
| :--- | :--- |
| 顶部（时间 / 日期 / 电量） | **64dp** |
| 底部（级别 / 秒数两个 pill） | **56dp** |
| 左右 | **40dp** |

数值都在 `res/values/dimens.xml` 里，觉得多了少了直接改。

### 5. 滑动关闭要拖太远

原来要拖屏幕高度的 28%，而且**图标会一路跟着手指走**，看不出"拖到哪就够了"。

现在：

- 解锁线设在 **屏幕高度的 25%** —— 也就是把中央圆钮从正中拖到屏幕 **3/4** 的位置
- **图标最多只跟到解锁线就停住**。停住那一刻就是"够了"，不会再诱导你继续往下拖
- 拖过线时圆钮放大一点、文案变成「松开即可关闭」，给一次明确反馈
- 松手即关；没过线弹回原位
- 顺带修掉"必须刚好按在圆钮上"的问题：现在**整块屏幕都能起手**

---

## 三、界面

主界面是 Material 3 卡片式：

```
┌──────────────────────────────────┐
│  幕布                             │
│  把屏幕调暗到只剩一层黑，设备照常运行  │
│  ╭──────────────────────────╮    │
│  │ 权限                      │    │
│  │ ◉ 显示在其他应用上层  已授权 │    │
│  │ ♿ 无障碍服务          未开启 │    │
│  │   开启后幕布可以连状态栏一起盖住│   │
│  │ ◉ 使用情况访问        未授权 │    │
│  │ ◉ 通知                已授权 │    │
│  ╰──────────────────────────╯    │
│  ╭──────────────────────────╮    │
│  │ 显示                      │    │
│  │ ☀ 亮度级别          5 级   │    │
│  │ ─────────●───────────     │    │
│  │ ⏱ 自动纯黑          30 秒  │    │
│  │ ──●──────────────────     │    │
│  ╰──────────────────────────╯    │
│  ┌────────── 启动幕布 ─────────┐  │
│  当前模式：普通悬浮窗 —— 状态栏图标会浮在…│
└──────────────────────────────────┘
```

幕布本体：

```
┌──────────────────────────────────┐
│  21:34                  ╭───────╮│
│  10月11日 周六            │🔋 66%││
│                          ╰───────╯│
│              ╭─────╮              │
│              │  🔒 │              │   ← 有使用情况访问权限时显示当前应用图标
│              ╰─────╯              │
│          向下拖动关闭幕布           │   ← 拖过 3/4 线后变成「松开即可关闭」
│                 ⌄                 │
│     左右边缘已屏蔽返回手势 · 上滑仍可返回桌面│
│     ╭────────╮  ╭────────╮        │
│     │  5 级  │  │  30 秒 │        │   ← 点击循环切换，和主界面同步
│     ╰────────╯  ╰────────╯        │
└──────────────────────────────────┘
```

---

## 四、文件

```
app/src/main/java/com/example/curtain/
├─ MainActivity.java                  主界面（Material 3）
├─ CurtainRouter.java             ★   路由器：选后端，所有入口都走这里
├─ CurtainAccessibilityService.java ★ 首选后端（TYPE_ACCESSIBILITY_OVERLAY）
├─ CurtainService.java                降级后端（前台服务 + TYPE_APPLICATION_OVERLAY）
├─ CurtainOverlay.java            ★   两条后端共用的"盖一层幕布"
├─ CurtainOverlayView.java            幕布本体：渐变黑 + 时间 + 中央关闭钮
├─ CurtainLaunchActivity.java         磁贴 -> 透明 Activity -> 后端
├─ CurtainTileService.java            快捷设置磁贴
├─ CurtainPrefs.java                  统一偏好访问（键名对外公开，供监听用）
└─ ForegroundApp.java                 取前台应用图标（UsageStats）
```

## 五、构建

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& .\android-env\build-apk.ps1 .\curtain
& .\android-env\build-apk.ps1 .\curtain -Task assembleRelease
& .\android-env\build-apk.ps1 .\curtain -Task lintDebug
```

产物在 `app/build/outputs/apk/{debug,release}/`。lint 0 error / 15 warning。

## 六、第一次使用

1. 「去授权」悬浮窗权限
2. **「去开启」无障碍服务** → 找到「幕布」并打开（想要状态栏一起黑必须做这步）
3. 授权通知（Android 13+）
4. 点「启动幕布」
5. 看主界面底部那行字：显示「无障碍覆盖层」才算真的把状态栏算进去了

---

## 作者

Deepseek · B站 @z7ui