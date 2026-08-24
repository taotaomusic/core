/** 验证码邮件的可替换文案，后续添加找回密码、通知邮件时复用同一份视觉模板。 */
export type VerificationEmailContent = {
  title: string;
  description: string;
  code: string;
};

/** 渲染兼容主流邮箱客户端的内联样式 HTML，同时提供纯文本降级内容。 */
export function renderVerificationEmail(content: VerificationEmailContent): { html: string; text: string } {
  const template = `<!doctype html>
<html lang="zh-CN">
  <body style="margin:0;padding:24px 12px;background:#fff7f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif;color:#332522;">
    <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0"><tr><td align="center">
      <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="max-width:520px;background:#ffffff;border-radius:24px;overflow:hidden;box-shadow:0 12px 32px rgba(139,67,49,.12);">
        <tr><td style="padding:32px 36px 28px;background:linear-gradient(135deg,#ff8a70,#ffb6a7);color:#ffffff;"><div style="font-size:26px;font-weight:700;letter-spacing:.5px;">桃桃音乐</div><div style="margin-top:8px;font-size:14px;opacity:.92;">让喜欢的声音，陪你久一点</div></td></tr>
        <tr><td style="padding:34px 36px 18px;"><h1 style="margin:0;font-size:22px;line-height:1.4;">{{title}}</h1><p style="margin:14px 0 24px;font-size:15px;line-height:1.7;color:#76635e;">{{description}}</p><div style="padding:18px 12px;border-radius:16px;background:#fff2ed;text-align:center;color:#df5f47;font-size:32px;font-weight:700;letter-spacing:10px;">{{code}}</div><p style="margin:24px 0 0;font-size:14px;line-height:1.7;color:#76635e;">验证码 10 分钟内有效，请勿将它分享给任何人。</p></td></tr>
        <tr><td style="padding:22px 36px 28px;font-size:12px;line-height:1.6;color:#a2918c;">如果不是你本人发起的操作，请忽略此邮件。<br>此邮件由系统自动发送，请勿直接回复。</td></tr>
      </table>
    </td></tr></table>
  </body>
</html>`;
  const values: Record<string, string> = {
    title: content.title,
    description: content.description,
    code: content.code,
  };
  const html = template.replace(/\{\{(title|description|code)\}\}/g, (_, key: string) => escapeHtml(values[key]));
  return { html, text: `${content.title}\n\n${content.description}\n验证码：${content.code}\n\n验证码 10 分钟内有效，请勿向任何人泄露。` };
}

/** 当前变量均来自服务端，但仍统一转义，模板扩展时不会意外引入 HTML 注入。 */
function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]!);
}
