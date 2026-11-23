# 满天星小程序打开海卓大脑

小程序用 WebView 打开浏览器正在使用的前端，地址带 `?view=h5`。这一参数只切换手机宽度下的界面，账号仍在页面里用手机号和密码登录。不单独打包 H5，也不做小程序登录或身份打通。

## 小程序接入

1. 将 `miniprogram/pages/haizhuo-h5/` 复制到小程序页面目录，并在 `app.json` 的 `pages` 中注册 `pages/haizhuo-h5/index`。
2. 把 `index.js` 里的 `h5Url` 改成正式前端地址 `https://<海卓大脑域名>/?view=h5`。在小程序后台把该 HTTPS 域名配成 WebView 业务域名。
3. 在满天星入口的点击事件中打开此页面。

```js
wx.navigateTo({ url: "/pages/haizhuo-h5/index" });
```

页面、`/api/` 和 `/agui/` 保持同源。反向代理需要关闭 SSE 响应缓冲。用户在 WebView 里完成登录后，会话保存在该 WebView 中。
