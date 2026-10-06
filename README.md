# BIU HUB V1.4.1 FINAL

基于用户指定的 V1.4.1 D1 版修复，不使用 V1.5.x。

## 本版处理
- D1 / Worker 改为 Supabase REST，同一账本供电脑与手机读取。
- 完全移除 ECharts 依赖，趋势图、分类图改为原生 SVG/CSS，避免 CDN/初始化顺序导致 `setOption` 崩溃。
- 修复 V1.4.1 后追加的编辑逻辑：原逻辑错误地把按月份存储的 DATA 当成数组。
- 编辑、删除、新增后统一保存到 Supabase。
- 空数据保持 KPI=0、趋势图零值、分类图空环。
- `renderMonth` 补回预算区域刷新。
- 去掉 Cloudflare Worker/D1 部署依赖，可作为纯静态站点部署。
