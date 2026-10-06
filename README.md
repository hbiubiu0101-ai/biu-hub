# BIU HUB · GitHub Pages + Supabase

正式前端：GitHub Pages  
云端数据：Supabase  
Cloudflare Workers / D1：不再作为本版本运行依赖。

## 部署
将本压缩包内的所有文件上传到 GitHub 仓库根目录。
GitHub → Settings → Pages → Deploy from a branch → main → /(root)

## 目录
- `/index.html`：BIU 工具中心
- `/tools/bookkeeping/index.html`：消费智记
- `/tools/travel/index.html`：智能旅游占位页
- `/database/supabase-init.sql`：Supabase 初始化 SQL

消费智记已连接现有 Supabase，并加入图表初始化保护。
