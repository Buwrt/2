import { randomUUID } from 'node:crypto';
import http from 'node:http';

/**
 * dsh-plugin-githup-context
 *
 * 让 AI **每轮自动**拿到 githup 当前页面/仓库，而不需要用户先问、
 * 也不需要模型自己去探测环境（那才是「费 token」的根源）。
 *
 * 数据来自 App 进程内写下的路由状态，经 127.0.0.1:3081/app/context?brief=1
 * 提供；不是截图、不是控件树、不需要任何 Android 系统权限。
 */
export const name = 'githup-context';
export const inject = [];

const ENDPOINT = 'http://127.0.0.1:3081/app/context';
const MAX = 700;
let cache = { at: 0, brief: '' };

function fetchBrief() {
  return new Promise((resolve) => {
    if (Date.now() - cache.at < 30000 && cache.brief) return resolve(cache.brief);
    let done = false;
    const finish = (v) => { if (!done) { done = true; resolve(v); } };
    try {
      const req = http.get(ENDPOINT, (res) => {
        let b = '';
        res.setEncoding('utf8');
        res.on('data', (d) => { if (b.length < 8192) b += d; });
        res.on('end', () => {
          try {
            const o = JSON.parse(b);
            const t = o && o.brief ? String(o.brief).slice(0, MAX) : '';
            if (t) cache = { at: Date.now(), brief: t };
            finish(t || cache.brief || '');
          } catch { finish(cache.brief || ''); }
        });
      });
      req.setTimeout(600, () => { try { req.destroy(); } catch {} finish(cache.brief || ''); });
      req.on('error', () => finish(cache.brief || ''));
    } catch { finish(cache.brief || ''); }
  });
}

export function apply(ctx) {
  const seen = new Map();   // sessionId:turn -> true，每轮只注入一次

  ctx.on('agent/pre-step', async (payload, next) => {
    const decision = await next();
    try {
      if (!decision || decision.kind !== 'enter' || !Array.isArray(decision.messages)) return decision;
      const key = (payload?.agent?.session?.id || 's') + ':' + payload?.turn;
      if (seen.get(key)) return decision;
      let hasUser = false;
      for (let i = decision.messages.length - 1; i >= 0; i -= 1) {
        const m = decision.messages[i];
        if (m && m.role === 'user' && m.source?.kind === 'user') { hasUser = true; break; }
      }
      if (!hasUser) return decision;                 // 后台/系统消息不注入
      const brief = await fetchBrief();
      if (!brief) return decision;
      seen.set(key, true);
      if (seen.size > 64) seen.delete(seen.keys().next().value);
      const message = {
        id: randomUUID(),
        role: 'user',
        source: { kind: 'githup-context', form: 'notice', summary: 'githup current page' },
        content: [{ type: 'text', text: brief }],
      };
      return { ...decision, messages: [...decision.messages, message] };
    } catch {
      return decision;                              // 任何异常都绝不影响引擎
    }
  });
}
