# LBlog 部署踩坑记录：从 SPA 刷新 404 到 SSL 与端口冲突

记录这次给博客（前端 blog-view + 后台 blog-cms + 后端 blog-api）配 HTTPS 过程中遇到的几个问题与最终方案。环境：阿里云 + 宝塔面板 + Nginx + Spring Boot。

## 最终架构

```
浏览器
  ├─ https://lblog.work          → web 站点 (443/SSL)        → blog-view 静态文件
  ├─ https://lblog.work/api/...  → web 站点反代              → 127.0.0.1:8091 (Spring Boot 前台接口)
  ├─ https://lblog.work/admin/...→ web 站点反代              → 127.0.0.1:8091 (Spring Boot 后台接口)
  └─ https://lblog.work:8081     → web_admin 站点 (8081/SSL) → blog-cms 静态文件
```

要点：

- 所有公网 HTTPS 请求由 Nginx 终止，Spring Boot 只在内网（8091）跑纯 HTTP，不需要管证书
- SSL 证书只在 `lblog.work` 一个站点维护，`8081` 站点复用同一对证书文件，续期一次到位
- 后端的 `/admin/` 与前台的根路径接口通过 Nginx 的 `location` 分流到同一个 Spring Boot 进程

## 问题一：Vue history 模式刷新 404

`blog-view` 和 `blog-cms` 都用 `mode: 'history'`，从根路径进入再跳转正常，直接刷新 `https://xxx/home` 之类的非根路径就报 404。

原因很简单：history 模式下，浏览器刷新会把整段路径当作真实 URL 发给服务器，Nginx 找不到对应的静态文件就 404，跟 Vue 路由没关系。

**修复：dev 环境**——给两个 vue.config.js 加上 `historyApiFallback: true`：

```js
// blog-cms/vue.config.js
devServer: {
  port: port,
  open: true,
  historyApiFallback: true,
  // ...
}

// blog-view/vue.config.js
module.exports = {
  devServer: {
    historyApiFallback: true,
  },
  // ...
}
```

**修复：生产环境**——在两个 Nginx 站点配置里加 SPA fallback：

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

## 问题二：HTTPS 直连 8090 报 ERR_SSL_PROTOCOL_ERROR

宝塔站点配了 SSL 后，前端 `baseURL` 写的是 `https://lblog.work:8090/`，浏览器报 `ERR_SSL_PROTOCOL_ERROR`，但改回 `http://lblog.work:8090/` 又能用。

原因：

- 宝塔的 SSL 是绑在「网站」（443 端口的 Nginx server 块）上的
- Spring Boot (`server.port: 8090`) 是一个**独立的 Java 进程**，它不知道也不关心 Nginx 上配的证书
- 浏览器对一个**裸 HTTP 端口**做 TLS 握手，自然失败

**修复方向**：让所有 HTTPS 流量统一走 Nginx 443，由 Nginx 反代到 Spring Boot 的 HTTP 端口。这样 SSL 维护点只有一个。

### 1. 改 web 站点配置（lblog.work，443）

在 `server { listen 443 ssl; ... }` 块里加：

```nginx
# 后台 CMS 接口反代到 Spring Boot
location /admin/ {
    proxy_pass http://127.0.0.1:8091/admin/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
}

# 前台接口反代到 Spring Boot（去掉 /api 前缀）
location /api/ {
    proxy_pass http://127.0.0.1:8091/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
}

location / {
    try_files $uri $uri/ /index.html;
}
```

`proxy_pass` 末尾带 `/` 的写法会把 `/api/site` 转发为后端的 `/site`，正好对上 Spring Boot 那些挂在根路径的前台接口。

### 2. 改前端 baseURL

```js
// blog-cms/src/util/request.js
baseURL: 'https://lblog.work/admin/'

// blog-view/src/plugins/axios.js
baseURL: 'https://lblog.work/api/'
```

不再硬编码端口号，避免暴露内部端口、避免 mixed content。

### 3. 改后端配置中的回显 URL

```yaml
# application-prod.yml
blog:
  api: https://lblog.work/api   # 原来是 https://lblog.work:${server.port}
```

否则邮件、RSS、sitemap 里链接还是带 `:8090`。

## 问题三：8090 端口被 Nginx 抢了，Spring Boot 启动后绑不上

改完上面那一套，访问 `https://lblog.work/api/site` 仍然 404。本机 curl 直连后端：

```bash
curl http://127.0.0.1:8090/site
# <html><head><title>404 Not Found</title></head>
# ... <hr><center>nginx</center> ...
```

返回的是 **Nginx 的 404 页面**，不是 Spring Boot 的响应。也就是说 8090 端口实际上是 Nginx 在监听，Spring Boot 启动时端口已被占用，绑不上。

回头看 `java.txt`（宝塔自动给 Java 项目生成的 Nginx 站点）：

```nginx
server {
    listen 8090;
    listen 443 ssl;
    server_name lblog.work;
    # ...里面没有任何 proxy_pass
}
```

这是宝塔创建 Java 项目时给的 **Nginx 配置壳**，本意是让你把 `proxy_pass` 加进去做反代用。但它直接 `listen 8090`，又没反代，就成了一个空壳挡在前面，把所有 `127.0.0.1:8090` 的请求接走然后 404。

我一开始想让用户直接删除这个站点，但用户不放心（误以为站点 = 后端进程）。这里要澄清：

| 名称 | 在宝塔哪里 | 是什么 |
|---|---|---|
| 网站 → blog-api-0 | 「网站」列表 | 一段 **Nginx 配置**（即 java.txt） |
| 后端 jar 进程 | 「Java 项目管理」 | 真正跑 Spring Boot 的进程 |

两者无关。删站点 = 删一段 Nginx 配置，jar 进程不动。

**最省事的修复：换端口给 Spring Boot**

不动宝塔站点，把 Spring Boot 的端口从 8090 改成 8091，让 java.txt 那个空壳继续在 8090 上空转：

```yaml
# application-prod.yml
server:
  port: 8091
```

同步把 `web.txt` 里两个 `proxy_pass` 的目标端口改成 `8091`。重启 jar，验证：

```bash
curl http://127.0.0.1:8091/site       # 返回 JSON {"code":200,...}
curl https://lblog.work/api/site      # 返回相同 JSON
```

通了。

## 问题四：HSTS 导致 http://lblog.work:8081 自动升级为 https，后台访问不了

后台 `blog-cms` 部署在 8081 端口，原来一直是 HTTP。配完 443 SSL 后，浏览器访问 `http://lblog.work:8081` 会**自己跳到** `https://lblog.work:8081`，但 8081 没配 SSL，TLS 握手失败 → 访问不了。用 `http://39.107.83.230:8081` 反而能访问。

原因是 `web.txt`（lblog.work 主站点）里有：

```nginx
add_header Strict-Transport-Security "max-age=31536000";
```

这个响应头叫 **HSTS**，告诉浏览器：未来一年内，访问 `lblog.work` 这个**域名**一律走 HTTPS。这是浏览器记住的行为，所以即使你在地址栏敲 `http://`，浏览器也会**先**升级再发请求。HSTS 绑域名，不绑 IP，所以用 IP 直连不受影响。

**修复：让 8081 也开 SSL，复用同一张证书**

```nginx
server {
    listen 8081 ssl;
    http2 on;
    server_name 39.107.83.230 lblog.work;
    # ...

    #SSL-START SSL相关配置，请勿删除或修改下一行带注释的404规则
    #error_page 404/404.html;
    ssl_certificate    /www/server/panel/vhost/cert/39.107.83.230/fullchain.pem;
    ssl_certificate_key    /www/server/panel/vhost/cert/39.107.83.230/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers EECDH+CHACHA20:EECDH+CHACHA20-draft:EECDH+AES128:RSA+AES128:EECDH+AES256:RSA+AES256:EECDH+3DES:RSA+3DES:!MD5;
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;
    error_page 497  https://$host:8081$request_uri;
    #SSL-END
}
```

证书路径直接指向 lblog.work 站点的证书文件，**两个站点共享同一对文件**。这样以后只在 lblog.work 续期一次，8081 站点跟着自动用上新证书，零额外操作。

### 宝塔保存校验的小坑

宝塔保存站点配置时会校验：

> 配置文件保存失败：请勿修改 SSL 相关配置中注释的 404 规则

它要求 `#SSL-START` 块里第一行必须是 `#error_page 404/404.html;` 这条注释（这是它的标记锚点）。我一开始为了 SSL-START 写了一行说明注释，结果保存失败。改回标准格式后通过：

```nginx
#SSL-START SSL相关配置，请勿删除或修改下一行带注释的404规则
#error_page 404/404.html;
ssl_certificate ...
```

## 证书续期怎么办

**自动续期（Let's Encrypt 免费证书）**：宝塔到期前自动重新签发，覆盖写入 `/www/server/panel/vhost/cert/39.107.83.230/fullchain.pem` 和 `privkey.pem`，并自动 reload nginx。8081 站点指向同一对文件，reload 后立刻生效，**什么都不用做**。

**手动上传证书**：只在宝塔 lblog.work 站点的 SSL 页面更新一次。**不要**再去 8081 站点的 SSL 页面单独上传，那会写到不同的路径下，和 web_admin.txt 里 `ssl_certificate` 的指向不一致。

**验证证书生效**：

```bash
echo | openssl s_client -connect lblog.work:443 -servername lblog.work 2>/dev/null | openssl x509 -noout -dates
echo | openssl s_client -connect lblog.work:8081 -servername lblog.work 2>/dev/null | openssl x509 -noout -dates
```

两个 `notAfter` 应当完全一致。

**兜底**：宝塔偶尔只 reload 主站点配置不会显式 reload 整个 nginx，万一发现旧证书没换可以手动：

```bash
nginx -s reload
```

或在宝塔「计划任务」里加一条每天凌晨执行的 `nginx -s reload`。

## 改动清单回顾

| 文件 | 改动 |
|---|---|
| `blog-cms/vue.config.js` | dev server 加 `historyApiFallback: true` |
| `blog-view/vue.config.js` | dev server 加 `historyApiFallback: true` |
| `blog-cms/src/util/request.js` | baseURL → `https://lblog.work/admin/` |
| `blog-view/src/plugins/axios.js` | baseURL → `https://lblog.work/api/` |
| `blog-api/.../application-prod.yml` | `server.port` 8090 → 8091；`blog.api` 去掉端口 |
| `web.txt`（lblog.work:443） | 加 `/admin/`、`/api/` 反代到 127.0.0.1:8091 + SPA fallback |
| `web_admin.txt`（8081） | 改成 `listen 8081 ssl;`，复用 lblog.work 证书 + SPA fallback |
| `java.txt`（blog-api-0） | 去掉 `listen 443 ssl` 与 HTTPS 强跳，避免抢 lblog.work:443 |

## 经验小结

1. **SSL 终止点要尽可能少**。让 Nginx 统一管 443，业务进程只跑内网 HTTP，证书续期一次到位。
2. **公网不要直接暴露业务端口**。8091 只在 127.0.0.1 用，外面看不见，攻击面小一截。
3. **HSTS 是浏览器侧记忆**，配置一旦下发就回不去，调试时用无痕窗口或临时换浏览器/IP 测试。
4. **宝塔自动生成的 Java 站点 Nginx 配置**默认会监听同一个端口，跟 Spring Boot 直接冲突。要么把它删掉让 Spring Boot 占用，要么换个端口绕开。
5. **改完前端必须重新 build 上传 dist**，浏览器还要清缓存或开无痕，否则在用旧的 JS。
6. **怀疑端口冲突时先在服务器本机 curl**，看返回的 `Server:` 头或 HTML 里的 `<center>nginx</center>`，能立刻看出是谁在响应。
