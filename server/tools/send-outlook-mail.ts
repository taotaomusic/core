/**
 * 独立的 Outlook.com 发信测试脚本。
 *
 * 使用方式：
 *   $env:OUTLOOK_CLIENT_ID = "在 Microsoft Entra 注册的应用客户端 ID"
 *   $env:OUTLOOK_TO = "收件人@example.com"
 *   $env:OUTLOOK_SUBJECT = "测试邮件"
 *   $env:OUTLOOK_BODY = "这是一封 API 测试邮件。"
 *   npx ts-node tools/send-outlook-mail.ts
 *
 * 脚本只使用设备码 OAuth，不读取邮箱密码，也不会加载 Nest 主进程。
 */

const clientId = process.env.OUTLOOK_CLIENT_ID;
const accessToken = process.env.OUTLOOK_ACCESS_TOKEN;
const to = process.env.OUTLOOK_TO;
const subject = process.env.OUTLOOK_SUBJECT ?? "桃桃音乐 API 发信测试";
const body = process.env.OUTLOOK_BODY ?? "这是一封通过 Microsoft Graph API 发送的测试邮件。";

if ((!clientId && !accessToken) || !to) {
    console.error("请设置 OUTLOOK_TO，以及 OUTLOOK_CLIENT_ID 或 OUTLOOK_ACCESS_TOKEN。");
    process.exit(1);
}

const tenant = "consumers";
const scope = "https://graph.microsoft.com/Mail.Send offline_access";
const deviceCodeUrl = `https://login.microsoftonline.com/${tenant}/oauth2/v2.0/devicecode`;
const tokenUrl = `https://login.microsoftonline.com/${tenant}/oauth2/v2.0/token`;

async function readJson(response: Response): Promise<Record<string, any>> {
    const data = await response.json() as Record<string, any>;
    if (!response.ok) {
        throw new Error(`${response.status} ${JSON.stringify(data)}`);
    }
    return data;
}

async function acquireAccessToken(): Promise<string> {
    if (accessToken) {
        return accessToken;
    }

    const deviceResponse = await fetch(deviceCodeUrl, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ client_id: clientId!, scope }),
    });
    const device = await readJson(deviceResponse);

    console.log(device.message);
    const interval = Number(device.interval ?? 5) * 1000;
    let token: Record<string, any>;

    while (true) {
        await new Promise((resolve) => setTimeout(resolve, interval));
        const tokenResponse = await fetch(tokenUrl, {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: new URLSearchParams({
                client_id: clientId!,
                grant_type: "urn:ietf:params:oauth:grant-type:device_code",
                device_code: device.device_code,
            }),
        });
        token = await tokenResponse.json() as Record<string, any>;
        if (tokenResponse.ok) break;
        if (token.error === "authorization_pending") continue;
        throw new Error(`${tokenResponse.status} ${JSON.stringify(token)}`);
    }

    return token.access_token;
}

async function main(): Promise<void> {
    const token = await acquireAccessToken();
    const sendResponse = await fetch("https://graph.microsoft.com/v1.0/me/sendMail", {
        method: "POST",
        headers: {
            Authorization: `Bearer ${token}`,
            "Content-Type": "application/json",
        },
        body: JSON.stringify({
            message: {
                subject,
                body: { contentType: "Text", content: body },
                toRecipients: [{ emailAddress: { address: to } }],
            },
            saveToSentItems: true,
        }),
    });

    if (!sendResponse.ok) {
        throw new Error(`${sendResponse.status} ${await sendResponse.text()}`);
    }
    console.log(`邮件已提交发送：${to}`);
}

main().catch((error: unknown) => {
    console.error("发信失败：", error instanceof Error ? error.message : error);
    process.exit(1);
});
