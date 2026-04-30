<template>
  <div class="login-container" ref="containerRef">
    <!-- 鼠标跟随光晕 -->
    <div class="cursor-glow" ref="cursorGlowRef"></div>

    <!-- 左侧品牌区 -->
    <div class="brand-section">
      <canvas ref="canvasRef" class="particle-canvas"></canvas>
      <div class="grid-overlay"></div>
      <div class="brand-content">
        <div class="brand-logo">
          <div class="logo-icon">
            <div class="logo-inner"></div>
            <div class="logo-ring"></div>
            <div class="logo-ring ring-outer"></div>
          </div>
        </div>
        <h1 class="brand-title">
          <span class="title-main">滔滔</span>
          <span class="title-en">TAO TAO</span>
        </h1>
        <p class="brand-slogan">智能对话，滔滔不绝</p>
        <div class="brand-mood">
          <span class="mood-tag" style="--delay:0.45s">洞察先机，把握每一次增长</span>
          <span class="mood-tag" style="--delay:0.58s">不放过任何一个可能</span>
          <span class="mood-tag" style="--delay:0.71s">商机，从一场对话开始</span>
          <span class="mood-tag" style="--delay:0.71s">滔滔不绝的，是对话也是商机</span>
        </div>
      </div>
      <div class="brand-bottom">
        <div class="divider-line"></div>
        <p class="powered-text">Powered by daren</p>
      </div>
    </div>

    <!-- 中间分隔 -->
    <div class="center-divider"></div>

    <!-- 右侧表单区 -->
    <div class="form-section">
      <div class="form-inner">
        <!-- 移动端品牌头 -->
        <div class="mobile-header">
          <div class="mini-logo">
            <div class="logo-inner"></div>
          </div>
          <span class="mini-title">滔滔</span>
        </div>

        <div class="form-header">
          <h2>欢迎回来</h2>
          <p>登录你的账号，继续探索</p>
        </div>

        <el-form
          ref="loginFormRef"
          :model="loginForm"
          :rules="loginRules"
          class="login-form"
          @keyup.enter="handleLogin"
        >
          <div class="field">
            <label>用户名</label>
            <el-form-item prop="username">
              <el-input
                ref="usernameInputRef"
                v-model="loginForm.username"
                placeholder="请输入用户名"
                size="large"
                :prefix-icon="User"
                class="form-input"
              >
                <template #suffix>
                  <span v-if="loginForm.username" class="clear-btn" @click="loginForm.username = ''">
                    <el-icon><CircleClose /></el-icon>
                  </span>
                </template>
              </el-input>
            </el-form-item>
          </div>

          <div class="field">
            <label>密码</label>
            <el-form-item prop="password">
              <el-input
                v-model="loginForm.password"
                type="password"
                placeholder="请输入密码"
                size="large"
                :prefix-icon="Lock"
                show-password
                class="form-input"
              />
            </el-form-item>
          </div>

          <button
            type="button"
            class="submit-btn"
            :class="{ 'is-loading': loading }"
            :disabled="loading"
            @click="handleLogin"
          >
            <span v-if="!loading" class="btn-label">登 入</span>
            <span v-else class="btn-loading"><i></i><i></i><i></i></span>
            <i class="btn-shine"></i>
          </button>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
  import { defineComponent, ref, reactive, onMounted, onBeforeUnmount } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
  import { User, Lock, CircleClose } from '@element-plus/icons-vue';
  import authService from '@/services/auth';
  import { agentScopeApi } from '@/services/agentScope';

  /* ========== 粒子网络 ========== */
  class ParticleNetwork {
    private canvas: HTMLCanvasElement;
    private ctx: CanvasRenderingContext2D;
    private particles: Array<{
      x: number; y: number; vx: number; vy: number; r: number; o: number;
    }> = [];
    private mouse = { x: -9999, y: -9999 };
    private w = 0;
    private h = 0;
    private rafId = 0;

    constructor(canvas: HTMLCanvasElement) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d')!;
      this.resize();
      this.seed();
      this.loop();
    }

    resize() {
      const box = this.canvas.parentElement!.getBoundingClientRect();
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      this.w = box.width;
      this.h = box.height;
      this.canvas.width = this.w * dpr;
      this.canvas.height = this.h * dpr;
      this.canvas.style.width = this.w + 'px';
      this.canvas.style.height = this.h + 'px';
      this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }

    seed() {
      const n = Math.min(Math.floor((this.w * this.h) / 15000), 65);
      this.particles = Array.from({ length: n }, () => ({
        x: Math.random() * this.w,
        y: Math.random() * this.h,
        vx: (Math.random() - 0.5) * 0.35,
        vy: (Math.random() - 0.5) * 0.35,
        r: Math.random() * 1.4 + 0.8,
        o: Math.random() * 0.5 + 0.3,
      }));
    }

    private loop = () => {
      this.ctx.clearRect(0, 0, this.w, this.h);
      const { particles: ps, mouse: m, ctx, w, h } = this;

      for (const p of ps) {
        p.x += p.vx;
        p.y += p.vy;
        if (p.x < 0 || p.x > w) p.vx *= -1;
        if (p.y < 0 || p.y > h) p.vy *= -1;

        const dx = m.x - p.x, dy = m.y - p.y;
        const d = Math.sqrt(dx * dx + dy * dy);
        if (d < 150 && d > 0) {
          p.vx += dx / d * 0.012;
          p.vy += dy / d * 0.012;
        }
        const spd = Math.sqrt(p.vx * p.vx + p.vy * p.vy);
        if (spd > 0.8) { p.vx *= 0.98; p.vy *= 0.98; }

        ctx.beginPath();
        ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(8,145,178,${p.o})`;
        ctx.fill();
      }

      const linkDist = 105;
      for (let i = 0; i < ps.length; i++) {
        for (let j = i + 1; j < ps.length; j++) {
          const dx = ps[i].x - ps[j].x, dy = ps[i].y - ps[j].y;
          const d = Math.sqrt(dx * dx + dy * dy);
          if (d < linkDist) {
            ctx.beginPath();
            ctx.moveTo(ps[i].x, ps[i].y);
            ctx.lineTo(ps[j].x, ps[j].y);
            ctx.strokeStyle = `rgba(6,182,212,${(1 - d / linkDist) * 0.2})`;
            ctx.lineWidth = 0.6;
            ctx.stroke();
          }
        }
        const dx = ps[i].x - m.x, dy = ps[i].y - m.y;
        const d = Math.sqrt(dx * dx + dy * dy);
        if (d < 150) {
          ctx.beginPath();
          ctx.moveTo(ps[i].x, ps[i].y);
          ctx.lineTo(m.x, m.y);
          ctx.strokeStyle = `rgba(34,211,238,${(1 - d / 150) * 0.28})`;
          ctx.lineWidth = 0.6;
          ctx.stroke();
        }
      }

      this.rafId = requestAnimationFrame(this.loop);
    };

    setMouse(x: number, y: number) { this.mouse.x = x; this.mouse.y = y; }
    refresh() { this.resize(); this.seed(); }
    destroy() { cancelAnimationFrame(this.rafId); this.particles = []; }
  }

  /* ========== 组件 ========== */
  export default defineComponent({
    name: 'Login',
    components: { User, Lock, CircleClose },
    setup() {
      const router = useRouter();
      const loginFormRef = ref<FormInstance>();
      const usernameInputRef = ref();
      const loading = ref(false);
      const canvasRef = ref<HTMLCanvasElement>();
      const cursorGlowRef = ref<HTMLElement>();

      let net: ParticleNetwork | null = null;

      const features = [
        '多智能体协同对话',
        '知识库精准检索',
        '流式输出实时响应',
        '企业级安全管控',
      ];

      const loginForm = reactive({ username: '', password: '' });

      const loginRules: FormRules = {
        username: [
          { required: true, message: '请输入用户名', trigger: 'blur' },
          { min: 3, max: 20, message: '用户名长度在 3 到 20 个字符', trigger: 'blur' },
        ],
        password: [
          { required: true, message: '请输入密码', trigger: 'blur' },
          { min: 6, max: 20, message: '密码长度在 6 到 20 个字符', trigger: 'blur' },
        ],
      };

      const handleLogin = async () => {
        if (!loginFormRef.value) return;
        try {
          await loginFormRef.value.validate();
          loading.value = true;
          const result = await authService.login({
            username: loginForm.username,
            password: loginForm.password,
          });
          if (result) {
            ElMessage.success('登录成功');
            if (result.userInfo.role === 'admin' || result.userInfo.role === 'super_admin') {
              router.push('/daren-agent');
            } else {
              let agentId = result.userInfo.agentId;
              if (!agentId) {
                try {
                  const res = await agentScopeApi.list();
                  const agents = res?.data?.data || res?.data || [];
                  if (agents.length > 0) {
                    agentId = (agents.find((a: any) => a.status === 'published') || agents[0]).id;
                  } else { ElMessage.warning('暂无可用的智能体'); return; }
                } catch (e) { ElMessage.error('获取智能体信息失败'); return; }
              }
              router.push(`/daren-agent/${agentId}/run`);
            }
          }
        } catch (error: any) {
          ElMessage.error(error.response?.data?.message || error.message || '登录失败，请检查用户名和密码');
        } finally { loading.value = false; }
      };

      const onMouseMove = (e: MouseEvent) => {
        if (cursorGlowRef.value) {
          cursorGlowRef.value.style.left = e.clientX + 'px';
          cursorGlowRef.value.style.top = e.clientY + 'px';
        }
        if (net && canvasRef.value) {
          const r = canvasRef.value.getBoundingClientRect();
          net.setMouse(e.clientX - r.left, e.clientY - r.top);
        }
      };

      onMounted(() => {
        if (canvasRef.value) net = new ParticleNetwork(canvasRef.value);
        document.addEventListener('mousemove', onMouseMove);
        window.addEventListener('resize', () => net?.refresh());
        setTimeout(() => usernameInputRef.value?.focus(), 800);
      });

      onBeforeUnmount(() => {
        net?.destroy();
        document.removeEventListener('mousemove', onMouseMove);
      });

      return {
        canvasRef, cursorGlowRef,
        loginFormRef, usernameInputRef,
        loginForm, loginRules, loading, features,
        handleLogin, User, Lock, CircleClose,
      };
    },
  });
</script>

<style scoped>
  /* ====== 布局 ====== */
  .login-container {
    display: flex;
    height: 100vh;
    width: 100vw;
    overflow: hidden;
    /* 浅蓝白渐变底色，透亮干净 */
    background: linear-gradient(135deg, #f0f9ff 0%, #e0f2fe 30%, #f8fafc 60%, #eff6ff 100%);
    position: relative;
  }

  /* ====== 鼠标光晕 ====== */
  .cursor-glow {
    position: fixed;
    width: 520px;
    height: 520px;
    border-radius: 50%;
    background: radial-gradient(circle, rgba(6,182,212,0.08) 0%, transparent 70%);
    pointer-events: none;
    transform: translate(-50%, -50%);
    z-index: 1;
    opacity: 0;
    transition: opacity 0.4s;
  }
  .login-container:hover .cursor-glow { opacity: 1; }

  /* ====== 左侧品牌区 ====== */
  .brand-section {
    flex: 1.25;
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    background: radial-gradient(ellipse 70% 60% at 50% 45%, rgba(6,182,212,0.08) 0%, transparent 100%);
  }

  .grid-overlay {
    position: absolute;
    inset: 0;
    background-image:
      linear-gradient(rgba(6,182,212,0.04) 1px, transparent 1px),
      linear-gradient(90deg, rgba(6,182,212,0.04) 1px, transparent 1px);
    background-size: 44px 44px;
    z-index: 1;
    pointer-events: none;
  }

  .particle-canvas {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    z-index: 2;
    opacity: 0;
    animation: fadeIn 1.6s ease 0.3s forwards;
  }

  .brand-content {
    position: relative;
    z-index: 3;
    text-align: center;
    animation: slideUp 0.8s ease 0.15s both;
  }

  .brand-logo { margin-bottom: 32px; }
  .logo-icon {
    position: relative;
    width: 88px;
    height: 88px;
    margin: 0 auto;
  }
  .logo-inner {
    position: absolute;
    top: 50%; left: 50%;
    width: 52px; height: 52px;
    transform: translate(-50%, -50%);
    background: linear-gradient(135deg, #06b6d4, #22d3ee 50%, #0ea5e9);
    border-radius: 16px;
    box-shadow: 0 8px 32px rgba(6,182,212,0.3), 0 2px 8px rgba(6,182,212,0.2);
  }
  .logo-ring {
    position: absolute;
    top: 50%; left: 50%;
    width: 74px; height: 74px;
    transform: translate(-50%, -50%);
    border: 1.5px solid rgba(6,182,212,0.25);
    border-radius: 50%;
    animation: pulseRing 3s ease-in-out infinite;
  }
  .logo-ring.ring-outer {
    width: 88px; height: 88px;
    border-color: rgba(6,182,212,0.1);
    animation-delay: -1.5s;
  }

  @keyframes pulseRing {
    0%, 100% { transform: translate(-50%, -50%) scale(1); opacity: 1; }
    50% { transform: translate(-50%, -50%) scale(1.08); opacity: 0.4; }
  }

  .brand-title {
    display: flex;
    flex-direction: column;
    align-items: center;
    margin: 0 0 12px;
  }
  .title-main {
    font-size: 48px;
    font-weight: 700;
    letter-spacing: 10px;
    background: linear-gradient(135deg, #0891b2 0%, #06b6d4 50%, #0ea5e9 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    background-clip: text;
    line-height: 1.2;
  }
  .title-en {
    font-size: 11px;
    color: #94a3b8;
    letter-spacing: 8px;
    margin-top: 4px;
  }

  .brand-slogan {
    font-size: 15px;
    color: #64748b;
    margin: 0 0 40px;
    letter-spacing: 3px;
  }

  .brand-mood {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 10px;
    margin-top: 0;
  }
  .mood-tag {
    display: inline-block;
    font-size: 13px;
    color: #64748b;
    padding: 6px 16px;
    border-radius: 20px;
    background: rgba(6,182,212,0.06);
    border: 1px solid rgba(6,182,212,0.1);
    animation: slideUp 0.55s ease both;
    animation-delay: var(--delay);
    transition: all 0.25s ease;
    letter-spacing: 1px;
  }
  .mood-tag:hover {
    color: #0891b2;
    background: rgba(6,182,212,0.1);
    border-color: rgba(6,182,212,0.25);
    transform: translateY(-1px);
  }

  .brand-bottom {
    position: absolute;
    bottom: 32px;
    left: 50%;
    transform: translateX(-50%);
    text-align: center;
    z-index: 3;
    animation: fadeIn 1s ease 1s both;
  }
  .divider-line {
    width: 48px; height: 1.5px;
    background: linear-gradient(90deg, transparent, rgba(6,182,212,0.4), rgba(14,165,233,0.3), transparent);
    margin: 0 auto 12px;
  }
  .powered-text {
    font-size: 11px;
    color: #94a3b8;
    margin: 0;
    letter-spacing: 1px;
  }

  /* ====== 分隔线 ====== */
  .center-divider {
    width: 1px;
    flex-shrink: 0;
    background: linear-gradient(to bottom, transparent 15%, rgba(6,182,212,0.06) 50%, transparent 85%);
  }

  /* ====== 右侧表单区 ====== */
  .form-section {
    flex: 0.75;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 48px 40px;
    background: linear-gradient(160deg, rgba(241,245,249,0.5) 0%, rgba(224,242,254,0.3) 100%);
    position: relative;
    z-index: 2;
  }

  .form-inner {
    width: 100%;
    max-width: 360px;
    animation: slideUp 0.8s ease 0.35s both;
  }

  .mobile-header {
    display: none;
    align-items: center;
    justify-content: center;
    gap: 10px;
    margin-bottom: 28px;
  }
  .mini-logo {
    width: 34px; height: 34px;
    position: relative;
  }
  .mini-logo .logo-inner {
    width: 22px; height: 22px;
    border-radius: 7px;
  }
  .mini-title {
    font-size: 20px;
    font-weight: 700;
    background: linear-gradient(135deg, #0891b2, #06b6d4);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    background-clip: text;
  }

  .form-header { margin-bottom: 36px; }
  .form-header h2 {
    font-size: 24px;
    font-weight: 600;
    color: #0f172a;
    margin: 0 0 8px;
  }
  .form-header p {
    font-size: 14px;
    color: #64748b;
    margin: 0;
  }

  /* ====== 表单控件 ====== */
  .field { margin-bottom: 24px; }
  .field label {
    display: block;
    font-size: 13px;
    color: #475569;
    margin-bottom: 8px;
    letter-spacing: 0.3px;
    font-weight: 500;
  }

  .login-form :deep(.el-form-item) { margin-bottom: 0; }
  .login-form :deep(.el-form-item__error) {
    color: #ef4444;
    font-size: 12px;
    padding-top: 6px;
    line-height: 1.2;
  }

  /* 白底输入框，干净透亮 */
  .form-input :deep(.el-input__wrapper) {
    background: rgba(255,255,255,0.85);
    border: 1px solid rgba(203,213,225,0.6);
    box-shadow: 0 1px 3px rgba(0,0,0,0.04), 0 1px 2px rgba(0,0,0,0.03) !important;
    border-radius: 10px;
    padding: 4px 14px;
    transition: all 0.25s ease;
    backdrop-filter: blur(8px);
  }
  .form-input :deep(.el-input__wrapper:hover) {
    border-color: rgba(6,182,212,0.4);
    background: rgba(255,255,255,0.95);
    box-shadow: 0 2px 8px rgba(6,182,212,0.08) !important;
  }
  .form-input :deep(.el-input__wrapper.is-focus) {
    border-color: rgba(6,182,212,0.6);
    box-shadow: 0 0 0 3px rgba(6,182,212,0.1), 0 2px 8px rgba(6,182,212,0.1) !important;
    background: #fff;
  }
  .form-input :deep(.el-input__inner) {
    color: #0f172a;
    font-size: 14px;
  }
  .form-input :deep(.el-input__inner::placeholder) {
    color: #94a3b8;
  }
  .form-input :deep(.el-input__prefix .el-icon) {
    color: #06b6d4;
    font-size: 16px;
  }

  /* 清除按钮 + 密码眼睛 */
  .form-input :deep(.el-input__suffix) {
    display: flex;
    align-items: center;
    gap: 4px;
  }
  .clear-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    color: #94a3b8;
    transition: color 0.2s;
    padding: 2px;
  }
  .clear-btn:hover {
    color: #475569;
  }
  .form-input :deep(.el-input__password),
  .form-input :deep(.el-input__suffix .el-icon) {
    color: #94a3b8;
    transition: color 0.2s;
  }
  .form-input :deep(.el-input__password:hover),
  .form-input :deep(.el-input__suffix .el-icon:hover) {
    color: #475569;
  }

  /* ====== 提交按钮 ====== */
  .submit-btn {
    position: relative;
    width: 100%;
    height: 48px;
    margin-top: 16px;
    border: none;
    border-radius: 10px;
    font-size: 15px;
    font-weight: 600;
    letter-spacing: 8px;
    color: #fff;
    background: linear-gradient(135deg, #0891b2 0%, #06b6d4 50%, #0ea5e9 100%);
    box-shadow: 0 4px 16px rgba(6,182,212,0.3), 0 1px 3px rgba(6,182,212,0.15);
    cursor: pointer;
    overflow: hidden;
    transition: all 0.3s ease;
    font-family: inherit;
  }
  .submit-btn:hover:not(:disabled) {
    transform: translateY(-1px);
    box-shadow: 0 6px 24px rgba(6,182,212,0.4), 0 2px 6px rgba(6,182,212,0.2);
  }
  .submit-btn:active:not(:disabled) {
    transform: translateY(0);
    box-shadow: 0 2px 8px rgba(6,182,212,0.25);
  }
  .submit-btn:disabled { cursor: not-allowed; opacity: 0.75; }

  .btn-label { position: relative; z-index: 1; }

  .btn-loading {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    position: relative;
    z-index: 1;
  }
  .btn-loading i {
    display: block;
    width: 6px; height: 6px;
    border-radius: 50%;
    background: #fff;
    animation: dotBounce 1.2s ease-in-out infinite;
  }
  .btn-loading i:nth-child(2) { animation-delay: 0.15s; }
  .btn-loading i:nth-child(3) { animation-delay: 0.3s; }
  @keyframes dotBounce {
    0%, 80%, 100% { transform: scale(0.5); opacity: 0.3; }
    40% { transform: scale(1); opacity: 1; }
  }

  .btn-shine {
    position: absolute;
    inset: 0;
    background: linear-gradient(105deg, transparent 35%, rgba(255,255,255,0.25) 45%, rgba(255,255,255,0.25) 55%, transparent 65%);
    transform: translateX(-130%);
    pointer-events: none;
  }
  .submit-btn:hover:not(:disabled) .btn-shine {
    transform: translateX(130%);
    transition: transform 0.55s ease;
  }

  /* ====== 动画 ====== */
  @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
  @keyframes slideUp { from { opacity: 0; transform: translateY(18px); } to { opacity: 1; transform: translateY(0); } }

  /* ====== 响应式 ====== */
  @media (max-width: 768px) {
    .login-container { flex-direction: column; }

    .brand-section {
      flex: none;
      height: 30vh;
      min-height: 180px;
    }
    .particle-canvas { display: none; }
    .grid-overlay { display: none; }
    .brand-slogan { margin-bottom: 0; font-size: 13px; }
    .title-main { font-size: 34px; letter-spacing: 6px; }
    .brand-bottom { display: none; }

    .center-divider { display: none; }

    .form-section {
      flex: 1;
      padding: 20px 24px 32px;
      background: transparent;
      overflow-y: auto;
    }

    .mobile-header { display: flex; }
  }

  @media (max-width: 400px) {
    .form-section { padding: 16px 18px 28px; }
    .form-header h2 { font-size: 20px; }
  }
</style>
